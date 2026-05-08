package com.lnathan.villager.brian;

import java.util.Random;

/**
 * Buffer circular de experiencias para DQN.
 * Almacena tuplas (s, a, r, s', done) y permite muestrear batches aleatorios.
 *
 * El muestreo aleatorio rompe la correlación temporal entre experiencias
 * consecutivas, lo que estabiliza el entrenamiento de la red neuronal.
 */
public class ReplayBuffer {

    //  Estructura de una experiencia 

    public static class Experience {
        public final float[]       state;
        public final int           action;
        public final float         reward;
        public final float[]       nextState;
        public final boolean       done;

        public Experience(float[] state, int action, float reward,
                          float[] nextState, boolean done) {
            this.state     = state;
            this.action    = action;
            this.reward    = reward;
            this.nextState = nextState;
            this.done      = done;
        }
    }

    //  Buffer circular 

    private final Experience[] buffer;
    private final int          capacity;
    private int                size    = 0;
    private int                writeIdx = 0;
    private final Random       random  = new Random();

    public ReplayBuffer(int capacity) {
        this.capacity = capacity;
        this.buffer   = new Experience[capacity];
    }

    //  API 

    /** Añade una experiencia. Sobreescribe la más antigua si el buffer está lleno. */
    public void add(float[] state, int action, float reward,
                    float[] nextState, boolean done) {
        buffer[writeIdx] = new Experience(state.clone(), action, reward,
                nextState.clone(), done);
        writeIdx = (writeIdx + 1) % capacity;
        if (size < capacity) size++;
    }

    /**
     * Muestrea un batch aleatorio sin reemplazo.
     * Devuelve null si no hay suficientes experiencias todavía.
     */
    public Experience[] sample(int batchSize) {
        if (size < batchSize) return null;

        Experience[] batch = new Experience[batchSize];
        // Fisher-Yates parcial para muestreo sin reemplazo
        int[] indices = new int[size];
        for (int i = 0; i < size; i++) indices[i] = i;
        for (int i = 0; i < batchSize; i++) {
            int j = i + random.nextInt(size - i);
            int tmp = indices[i]; indices[i] = indices[j]; indices[j] = tmp;
            batch[i] = buffer[indices[i]];
        }
        return batch;
    }

    public int  size()     { return size; }
    public int  capacity() { return capacity; }
    public boolean isReady(int minSize) { return size >= minSize; }
}