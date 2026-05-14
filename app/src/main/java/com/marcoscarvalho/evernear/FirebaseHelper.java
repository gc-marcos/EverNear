package com.marcoscarvalho.evernear;

import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class FirebaseHelper {

    private static final FirebaseFirestore db = FirebaseFirestore.getInstance();

    public interface Callback<T> {
        void onResult(T result);
        void onError(Exception e);
    }

    public static String gerarCodigoVinculo() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder salt = new StringBuilder();
        Random rnd = new Random();
        while (salt.length() < 6) {
            salt.append(chars.charAt((int) (rnd.nextFloat() * chars.length())));
        }
        return salt.toString();
    }

    /**
     * Salva o usuário no Firestore.
     * Para pacientes:
     *   - codigoVinculo: código de 6 dígitos para o cuidador escanear
     *   - cuidadoresVinculados: lista vazia (até 3 cuidadores podem ser adicionados)
     * Para cuidadores:
     *   - pacientesVinculados: lista vazia
     * @param telefone número do paciente — null para cuidadores
     */
    public static void salvarUsuario(String uid, String nome, String email,
                                      String tipo, String telefone,
                                      Callback<String> callback) {
        Map<String, Object> user = new HashMap<>();
        user.put("nome", nome);
        user.put("email", email);
        user.put("tipo", tipo);
        if (telefone != null && !telefone.isEmpty()) {
            user.put("telefone", telefone);
        }

        String codigoGerado;
        if ("paciente".equals(tipo) || "patient".equals(tipo)) {
            codigoGerado = gerarCodigoVinculo();
            user.put("codigoVinculo", codigoGerado);
            // Array de até 3 cuidadores em ordem de prioridade de escalada
            user.put("cuidadoresVinculados", new ArrayList<String>());
        } else {
            codigoGerado = null;
            user.put("pacientesVinculados", new ArrayList<String>());
        }

        final String codigoFinal = codigoGerado;
        db.collection("users").document(uid)
                .set(user)
                .addOnSuccessListener(aVoid -> callback.onResult(codigoFinal))
                .addOnFailureListener(callback::onError);
    }

    public static void salvarApelido(String uid, String apelido, Callback<Void> callback) {
        db.collection("users").document(uid)
                .update("apelido", apelido)
                .addOnSuccessListener(aVoid -> callback.onResult(null))
                .addOnFailureListener(callback::onError);
    }

    public static void buscarPacientePorCodigo(String codigo, Callback<DocumentSnapshot> callback) {
        db.collection("users")
                .whereEqualTo("codigoVinculo", codigo)
                .get()
                .addOnSuccessListener(qs -> {
                    if (!qs.isEmpty()) callback.onResult(qs.getDocuments().get(0));
                    else callback.onResult(null);
                })
                .addOnFailureListener(callback::onError);
    }

    /**
     * Vincula cuidador ao paciente:
     *  - Adiciona uidPaciente em pacientesVinculados do cuidador (já existia)
     *  - Adiciona uidCuidador em cuidadoresVinculados do paciente (array, posição = prioridade)
     *
     * A posição no array define a ordem de escalada de alertas:
     *  posição 0 → recebe primeiro; se não confirmar em 5 min → posição 1 → posição 2
     */
    public static void vincularPacienteCuidador(String uidCuidador, String uidPaciente,
                                                 Callback<Void> callback) {
        // 1) Adiciona paciente na lista do cuidador
        db.collection("users").document(uidCuidador)
                .update("pacientesVinculados", FieldValue.arrayUnion(uidPaciente))
                .addOnSuccessListener(aVoid -> {
                    // 2) Adiciona cuidador na lista do paciente (ordem = prioridade de escalada)
                    db.collection("users").document(uidPaciente)
                            .update("cuidadoresVinculados", FieldValue.arrayUnion(uidCuidador))
                            .addOnSuccessListener(aVoid2 -> callback.onResult(null))
                            .addOnFailureListener(callback::onError);
                })
                .addOnFailureListener(callback::onError);
    }

    /**
     * Atualiza o BPM atual do paciente (throttled pelo chamador — sem callback).
     */
    public static void atualizarBpm(String uidPaciente, int bpm) {
        Map<String, Object> updates = new HashMap<>();
        updates.put("ultimoBpm", bpm);
        updates.put("ultimoBpmTimestamp", FieldValue.serverTimestamp());
        db.collection("users").document(uidPaciente).update(updates);
    }

    /**
     * Salva os limites de calibração no perfil do paciente.
     */
    public static void salvarBaseline(String uidPaciente, int baseline, int min, int max) {
        Map<String, Object> updates = new HashMap<>();
        updates.put("bpmBaseline", baseline);
        updates.put("bpmMin", min);
        updates.put("bpmMax", max);
        db.collection("users").document(uidPaciente).update(updates);
    }

    /**
     * Cria um documento de alerta na coleção `alerts/` para um cuidador específico.
     * O campo `prioridade` indica qual cuidador recebeu (0=primeiro, 1=segundo, 2=terceiro).
     * O callback retorna o ID do documento criado, usado pela lógica de escalada.
     */
    public static void enviarAlerta(String uidPaciente, String nomePaciente,
                                     String uidCuidador, int bpm, String tipo,
                                     int prioridade, Callback<String> callback) {
        Map<String, Object> alerta = new HashMap<>();
        alerta.put("pacienteId", uidPaciente);
        alerta.put("pacienteNome", nomePaciente);
        alerta.put("cuidadorId", uidCuidador);   // UID do CUIDADOR — nunca do paciente
        alerta.put("bpm", bpm);
        alerta.put("tipo", tipo);
        alerta.put("prioridade", prioridade);
        alerta.put("timestamp", FieldValue.serverTimestamp());
        alerta.put("acknowledged", false);

        db.collection("alerts").add(alerta)
                .addOnSuccessListener(docRef -> {
                    if (callback != null) callback.onResult(docRef.getId());
                })
                .addOnFailureListener(e -> {
                    if (callback != null) callback.onError(e);
                });
    }

    /** Sobrecarga sem prioridade (emergência manual — vai para todos ou para o primeiro). */
    public static void enviarAlerta(String uidPaciente, String nomePaciente,
                                     String uidCuidador, int bpm, String tipo,
                                     Callback<String> callback) {
        enviarAlerta(uidPaciente, nomePaciente, uidCuidador, bpm, tipo, 0, callback);
    }

    /**
     * Verifica se um alerta ainda não foi confirmado.
     * Usado pela lógica de escalada para decidir se deve notificar o próximo cuidador.
     */
    public static void alertaFoiConfirmado(String alertaId, Callback<Boolean> callback) {
        db.collection("alerts").document(alertaId).get()
                .addOnSuccessListener(doc -> {
                    if (!doc.exists()) {
                        callback.onResult(true); // documento removido = considerado confirmado
                        return;
                    }
                    Boolean acknowledged = doc.getBoolean("acknowledged");
                    callback.onResult(Boolean.TRUE.equals(acknowledged));
                })
                .addOnFailureListener(e -> callback.onResult(false));
    }
}
