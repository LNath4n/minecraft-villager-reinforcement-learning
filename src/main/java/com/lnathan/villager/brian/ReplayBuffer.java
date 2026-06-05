package com.lnathan.villager.brian;

import java.util.Random;

/**
 * Buffer circular de experiencias para DQN.
 * Almacena tuplas (s, a, r, s', done) y permite muestrear batches aleatorios.
 *
 * El muestreo aleatorio rompe la correlación temporal entre experiencias
 * consecutivas, lo que estabiliza el entrenamiento de la red neuronal.
 *
 * FIX: el sampling anterior usaba Fisher-Yates sobre un array de size=10000
 * elementos para sacar solo batchSize=32. Ahora usa sampling aleatorio simple
 * con detección de colisiones O(batchSize²) — prácticamente gratis con
 * batchSize=32 y capacity=10000 (probabilidad de colisión ~5%).
 */
public class ReplayBuffer {

    // Estructura de una experiencia

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

    // Buffer circular

    private final Experience[] buffer;
    private final int          capacity;
    private int                size     = 0;
    private int                writeIdx = 0;
    private final Random       random   = new Random();

    public ReplayBuffer(int capacity) {
        this.capacity = capacity;
        this.buffer   = new Experience[capacity];
    }

    // API

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
     *
     * FIX: antes se inicializaba un array de `size` elementos y se hacía
     * Fisher-Yates completo — O(size) por cada sample. Con size=10000 y
     * batchSize=32 eso es 10000 ops para sacar 32 elementos.
     *
     * Ahora: sampling aleatorio con detección lineal de duplicados — O(batchSize²).
     * Con batchSize=32 son como máximo 32*32=1024 comparaciones en el peor caso,
     * pero en promedio muchas menos. Para buffers grandes (>1000) la probabilidad
     * de colisión es baja y el loop termina rápido.
     */
    public Experience[] sample(int batchSize) {
        if (size < batchSize) return null;

        Experience[] batch   = new Experience[batchSize];
        int[]        indices = new int[batchSize];
        int          picked  = 0;

        while (picked < batchSize) {
            int candidate = random.nextInt(size);

            // Verificar que no esté ya en el batch
            boolean duplicate = false;
            for (int i = 0; i < picked; i++) {
                if (indices[i] == candidate) { duplicate = true; break; }
            }
            if (!duplicate) {
                indices[picked] = candidate;
                batch[picked]   = buffer[candidate];
                picked++;
            }
        }

        return batch;
    }

    public int     size()               { return size; }
    public int     capacity()           { return capacity; }
    public boolean isReady(int minSize) { return size >= minSize; }
}