package com.lnathan.villager.brian;

/**
 * Red objetivo (target network) para DQN estable.
 *
 * Es una copia congelada de la red principal que solo se actualiza
 * cada N steps. Esto evita el problema de "perseguir un blanco móvil":
 * si usáramos la red principal para calcular tanto la predicción como
 * el target Q en cada update, los pesos cambian el target en cada paso
 * y el aprendizaje diverge.
 *
 * La red objetivo en cambio permanece fija durante muchos steps,
 * dando al optimizador un objetivo estable para converger.
 */
public class TargetNetwork {

    private final NeuralNetwork net;
    private final int           updateFrequency; // steps entre cada sync
    private int                 stepsSinceUpdate = 0;

    public TargetNetwork(int[] layerSizes, float learningRate, int updateFrequency) {
        this.net             = new NeuralNetwork(layerSizes, learningRate);
        this.updateFrequency = updateFrequency;
    }

    //  Forward 

    /** Calcula Q-values con los pesos congelados. */
    public float[] forward(float[] state) {
        return net.forward(state);
    }

    //  Sincronización 

    /**
     * Llama en cada step de entrenamiento.
     * Copia pesos de la red principal cada {@code updateFrequency} llamadas.
     *
     * @param mainNet red principal
     * @return true si se realizó una sincronización
     */
    public boolean maybeUpdate(NeuralNetwork mainNet) {
        stepsSinceUpdate++;
        if (stepsSinceUpdate >= updateFrequency) {
            sync(mainNet);
            stepsSinceUpdate = 0;
            return true;
        }
        return false;
    }

    /** Fuerza una sincronización inmediata con la red principal. */
    public void sync(NeuralNetwork mainNet) {
        net.setWeights(mainNet.cloneWeights(), mainNet.cloneBiases());
    }

    public int getStepsSinceUpdate() { return stepsSinceUpdate; }
}