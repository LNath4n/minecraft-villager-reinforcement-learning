package com.lnathan.villager.brian;

import java.io.*;
import java.nio.file.*;
import java.util.Random;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Red neuronal fully-connected con backpropagation.
 * Arquitectura: 7 → 32 → 32 → 10 (configurable).
 * Activación oculta: ReLU. Salida: lineal (Q-values).
 * Sin dependencias externas — solo arrays float[][].
 *
 * FIX: ReadWriteLock en forward() y train() para eliminar el data race
 * cuando el entrenamiento asíncrono y el server thread acceden
 * concurrentemente a los pesos.
 *
 * Múltiples forwards pueden correr en paralelo (read lock compartido).
 * Un solo train() bloquea todos los forwards hasta terminar (write lock exclusivo).
 */
public class NeuralNetwork implements Serializable {

    private static final long serialVersionUID = 1L;

    // Pesos y biases
    // weights[l][j][i] = peso desde neurona i (capa l) hasta neurona j (capa l+1)
    // biases[l][j]     = bias de neurona j en capa l+1
    private float[][][] weights;
    private float[][]   biases;

    // Configuración
    private final int[] layerSizes; // ej: {18, 64, 64, 10}
    private final float learningRate;
    private static final Random RAND = new Random();

    // FIX: lock para acceso concurrente seguro entre server thread y trainer thread
    private final ReadWriteLock rwLock = new ReentrantReadWriteLock();

    // Constructor

    public NeuralNetwork(int[] layerSizes, float learningRate) {
        this.layerSizes   = layerSizes;
        this.learningRate = learningRate;
        initWeights();
    }

    /** Inicialización He (buena para ReLU): W ~ N(0, sqrt(2/fan_in)) */
    private void initWeights() {
        int numLayers = layerSizes.length - 1;
        weights = new float[numLayers][][];
        biases  = new float[numLayers][];

        for (int l = 0; l < numLayers; l++) {
            int fanIn  = layerSizes[l];
            int fanOut = layerSizes[l + 1];
            float std  = (float) Math.sqrt(2.0 / fanIn);

            weights[l] = new float[fanOut][fanIn];
            biases[l]  = new float[fanOut];

            for (int j = 0; j < fanOut; j++) {
                biases[l][j] = 0f;
                for (int i = 0; i < fanIn; i++) {
                    weights[l][j][i] = (float) (RAND.nextGaussian() * std);
                }
            }
        }
    }

    // Forward pass

    /**
     * Propaga la entrada y devuelve los Q-values de salida.
     *
     * FIX: read lock — permite múltiples aldeanos haciendo forward
     * simultáneamente, pero bloquea si train() tiene el write lock.
     */
    public float[] forward(float[] input) {
        rwLock.readLock().lock();
        try {
            float[] current = input;
            for (int l = 0; l < weights.length; l++) {
                current = layer(current, weights[l], biases[l], l < weights.length - 1);
            }
            return current;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /** Una capa: z = W·x + b, luego ReLU (si no es la última capa). */
    private float[] layer(float[] input, float[][] w, float[] b, boolean relu) {
        int out = w.length;
        float[] result = new float[out];
        for (int j = 0; j < out; j++) {
            float sum = b[j];
            for (int i = 0; i < input.length; i++) {
                sum += w[j][i] * input[i];
            }
            result[j] = relu ? Math.max(0f, sum) : sum;
        }
        return result;
    }

    // Backpropagation

    /**
     * Actualiza los pesos dado un batch de experiencias.
     *
     * FIX: write lock exclusivo — bloquea todos los forwards mientras
     * los pesos se están actualizando. Es la única operación que escribe.
     *
     * @param inputs   batch de vectores de estado [batchSize][inputSize]
     * @param targets  Q-values objetivo [batchSize][numActions]
     */
    public void train(float[][] inputs, float[][] targets) {
        int numLayers = weights.length;

        // Acumuladores de gradiente (para hacer media del batch)
        float[][][] dW = new float[numLayers][][];
        float[][]   dB = new float[numLayers][];
        for (int l = 0; l < numLayers; l++) {
            dW[l] = new float[weights[l].length][weights[l][0].length];
            dB[l] = new float[biases[l].length];
        }

        // Forward + backward por cada experiencia del batch
        // (sin lock aquí — los inputs/targets ya son copias locales)
        for (int b = 0; b < inputs.length; b++) {
            float[][] activations = new float[numLayers + 1][];
            float[][] zValues     = new float[numLayers][];
            activations[0] = inputs[b];

            for (int l = 0; l < numLayers; l++) {
                boolean isLast = (l == numLayers - 1);
                // FIX: accedemos a weights/biases localmente — el write lock
                // los protegerá en el bloque de apply al final
                zValues[l]         = computeZ(activations[l], weights[l], biases[l]);
                activations[l + 1] = applyActivation(zValues[l], !isLast);
            }

            float[][] deltas = new float[numLayers][];

            float[] output = activations[numLayers];
            deltas[numLayers - 1] = new float[output.length];
            for (int j = 0; j < output.length; j++) {
                deltas[numLayers - 1][j] = output[j] - targets[b][j];
            }

            for (int l = numLayers - 2; l >= 0; l--) {
                int size = weights[l].length;
                deltas[l] = new float[size];
                for (int j = 0; j < size; j++) {
                    float sum = 0f;
                    for (int k = 0; k < deltas[l + 1].length; k++) {
                        sum += weights[l + 1][k][j] * deltas[l + 1][k];
                    }
                    deltas[l][j] = sum * (zValues[l][j] > 0 ? 1f : 0f);
                }
            }

            for (int l = 0; l < numLayers; l++) {
                for (int j = 0; j < deltas[l].length; j++) {
                    dB[l][j] += deltas[l][j];
                    for (int i = 0; i < activations[l].length; i++) {
                        dW[l][j][i] += deltas[l][j] * activations[l][i];
                    }
                }
            }
        }

        // FIX: write lock exclusivo solo en la aplicación de gradientes
        // (la parte computacionalmente pesada del backprop corrió sin lock)
        rwLock.writeLock().lock();
        try {
            float scale = learningRate / inputs.length;
            for (int l = 0; l < numLayers; l++) {
                for (int j = 0; j < weights[l].length; j++) {
                    biases[l][j] -= scale * dB[l][j];
                    for (int i = 0; i < weights[l][j].length; i++) {
                        weights[l][j][i] -= scale * dW[l][j][i];
                    }
                }
            }
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    // Helpers internos

    private float[] computeZ(float[] input, float[][] w, float[] b) {
        float[] z = new float[w.length];
        for (int j = 0; j < w.length; j++) {
            z[j] = b[j];
            for (int i = 0; i < input.length; i++) z[j] += w[j][i] * input[i];
        }
        return z;
    }

    private float[] applyActivation(float[] z, boolean relu) {
        float[] a = new float[z.length];
        for (int i = 0; i < z.length; i++) a[i] = relu ? Math.max(0f, z[i]) : z[i];
        return a;
    }

    // Copia de pesos (para red objetivo)

    /**
     * Devuelve una copia profunda de los pesos de esta red.
     * FIX: read lock para evitar race con train() asíncrono.
     */
    public float[][][] cloneWeights() {
        rwLock.readLock().lock();
        try {
            float[][][] copy = new float[weights.length][][];
            for (int l = 0; l < weights.length; l++) {
                copy[l] = new float[weights[l].length][];
                for (int j = 0; j < weights[l].length; j++) {
                    copy[l][j] = weights[l][j].clone();
                }
            }
            return copy;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public float[][] cloneBiases() {
        rwLock.readLock().lock();
        try {
            float[][] copy = new float[biases.length][];
            for (int l = 0; l < biases.length; l++) copy[l] = biases[l].clone();
            return copy;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public void setWeights(float[][][] w, float[][] b) {
        rwLock.writeLock().lock();
        try {
            this.weights = w;
            this.biases  = b;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    // Persistencia

    public void save(Path file) throws IOException {
        rwLock.readLock().lock();
        try (ObjectOutputStream oos = new ObjectOutputStream(
                new BufferedOutputStream(Files.newOutputStream(file)))) {
            oos.writeObject(weights);
            oos.writeObject(biases);
            oos.writeFloat(learningRate);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @SuppressWarnings("unchecked")
    public void load(Path file) throws IOException, ClassNotFoundException {
        try (ObjectInputStream ois = new ObjectInputStream(
                new BufferedInputStream(Files.newInputStream(file)))) {
            float[][][] newWeights = (float[][][]) ois.readObject();
            float[][]   newBiases  = (float[][])   ois.readObject();
            ois.readFloat();
            // FIX: write lock al asignar los pesos cargados
            rwLock.writeLock().lock();
            try {
                this.weights = newWeights;
                this.biases  = newBiases;
            } finally {
                rwLock.writeLock().unlock();
            }
        }
    }
}