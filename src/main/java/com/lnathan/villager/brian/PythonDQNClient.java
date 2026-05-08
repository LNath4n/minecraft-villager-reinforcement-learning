package com.lnathan.villager.brian;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;


public class PythonDQNClient {

    private static final String BASE_URL = "http://localhost:5000";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    private final String villagerUUID;

    public PythonDQNClient(String villagerUUID) {
        this.villagerUUID = villagerUUID;
    }

    //  Enviar reporte al monitor 

    /**
     * Reporta la decisión tomada por Java al monitor Python (solo visualización).
     *
     * @param action       Nombre de la acción ("EAT", "FLEE", etc.)
     * @param actionIdx    Índice de la acción
     * @param reward       Recompensa calculada en Java
     * @param qValues      Q-values calculados en Java (puede ser null o vacío)
     * @param state        float[13] con el vector de estado
     * @param epsilon      Epsilon actual (exploración)
     * @param mode         "greedy" o "random"
     * @param step         Número de step global
     * @param buffer       Tamaño del replay buffer
     * @param socialPoints Puntos sociales acumulados (0 si no aplica)
     */
    public void report(String action, int actionIdx, float reward,
                       float[] qValues, float[] state,
                       float epsilon, String mode,
                       int step, int buffer, int socialPoints) {
        try {
            String body = buildReportJson(action, actionIdx, reward, qValues,
                    state, epsilon, mode, step, buffer, socialPoints);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/api/report"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(3))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString());
            // sendAsync para no bloquear el tick del aldeano

        } catch (Exception e) {
            System.err.println("[PythonMonitor] No disponible: " + e.getMessage());
        }
    }

    /** Sobrecarga sin socialPoints (usa 0 por defecto). */
    public void report(String action, int actionIdx, float reward,
                       float[] qValues, float[] state,
                       float epsilon, String mode,
                       int step, int buffer) {
        report(action, actionIdx, reward, qValues, state, epsilon, mode, step, buffer, 0);
    }

    /** Sobrecarga sin q_values (los omite del JSON). */
    public void report(String action, int actionIdx, float reward,
                       float[] state, float epsilon, String mode,
                       int step, int buffer) {
        report(action, actionIdx, reward, null, state, epsilon, mode, step, buffer, 0);
    }

    public void clear() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/api/clear"))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            System.err.println("[PythonMonitor] Clear falló: " + e.getMessage());
        }
    }

    //  JSON manual 

    private String buildReportJson(String action, int actionIdx, float reward,
                                   float[] qValues, float[] state,
                                   float epsilon, String mode,
                                   int step, int buffer, int socialPoints) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"uuid\":\"").append(villagerUUID).append("\",");
        sb.append("\"action\":\"").append(action).append("\",");
        sb.append("\"action_idx\":").append(actionIdx).append(",");
        sb.append("\"reward\":").append(String.format("%.4f", reward)).append(",");

        // q_values (opcional)
        if (qValues != null && qValues.length > 0) {
            sb.append("\"q_values\":[");
            for (int i = 0; i < qValues.length; i++) {
                if (i > 0) sb.append(",");
                sb.append(String.format("%.4f", qValues[i]));
            }
            sb.append("],");
        } else {
            sb.append("\"q_values\":[],");
        }

        // state
        sb.append("\"state\":[");
        for (int i = 0; i < state.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(String.format("%.4f", state[i]));
        }
        sb.append("],");

        sb.append("\"epsilon\":").append(String.format("%.4f", epsilon)).append(",");
        sb.append("\"mode\":\"").append(mode).append("\",");
        sb.append("\"step\":").append(step).append(",");
        sb.append("\"buffer\":").append(buffer).append(",");
        sb.append("\"social_points\":").append(socialPoints);
        sb.append("}");
        return sb.toString();
    }
}