package com.marcoscarvalho.evernear;

import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

public class FirebaseHelper {

    private static FirebaseFirestore db = FirebaseFirestore.getInstance();

    public interface Callback<T> {
        void onResult(T result);
        void onError(Exception e);
    }

    public static String gerarCodigoVinculo() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder salt = new StringBuilder();
        Random rnd = new Random();
        while (salt.length() < 6) {
            int index = (int) (rnd.nextFloat() * chars.length());
            salt.append(chars.charAt(index));
        }
        return salt.toString();
    }

    /**
     * Salva o usuário no Firestore.
     * Para pacientes, o callback retorna o código de vínculo gerado (String).
     * Para cuidadores, o callback retorna null.
     * @param telefone número do paciente — pode ser null para cuidadores
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
            user.put("cuidadorVinculado", null);
        } else {
            codigoGerado = null;
            user.put("pacientesVinculados", new java.util.ArrayList<String>());
        }

        final String codigoFinal = codigoGerado;

        db.collection("users").document(uid)
                .set(user)
                .addOnSuccessListener(aVoid -> {
                    // Após confirmação do Firestore, retorna o código gerado (ou null para cuidador)
                    callback.onResult(codigoFinal);
                })
                .addOnFailureListener(callback::onError);
    }

    /**
     * Salva ou atualiza o apelido do usuário no Firestore.
     */
    public static void salvarApelido(String uid, String apelido, Callback<Void> callback) {
        db.collection("users").document(uid)
                .update("apelido", apelido)
                .addOnSuccessListener(aVoid -> callback.onResult(null))
                .addOnFailureListener(callback::onError);
    }

    public static void buscarPacientePorCodigo(String codigo, Callback<DocumentSnapshot> callback) {
        // Busca pelo código em ambos os tipos possíveis ("patient" e "paciente")
        db.collection("users")
                .whereEqualTo("codigoVinculo", codigo)
                .get()
                .addOnSuccessListener(queryDocumentSnapshots -> {
                    if (!queryDocumentSnapshots.isEmpty()) {
                        callback.onResult(queryDocumentSnapshots.getDocuments().get(0));
                    } else {
                        callback.onResult(null);
                    }
                })
                .addOnFailureListener(callback::onError);
    }

    public static void vincularPacienteCuidador(String uidCuidador, String uidPaciente, Callback<Void> callback) {
        db.collection("users").document(uidCuidador)
                .update("pacientesVinculados", FieldValue.arrayUnion(uidPaciente))
                .addOnSuccessListener(aVoid -> {
                    db.collection("users").document(uidPaciente)
                            .update("cuidadorVinculado", uidCuidador)
                            .addOnSuccessListener(aVoid2 -> callback.onResult(null))
                            .addOnFailureListener(callback::onError);
                })
                .addOnFailureListener(callback::onError);
    }

    /**
     * Atualiza o BPM atual do paciente no Firestore (em tempo real).
     * Usado para o cuidador acompanhar continuamente.
     */
    public static void atualizarBpm(String uidPaciente, int bpm) {
        Map<String, Object> updates = new HashMap<>();
        updates.put("ultimoBpm", bpm);
        updates.put("ultimoBpmTimestamp", FieldValue.serverTimestamp());
        db.collection("users").document(uidPaciente).update(updates);
        // Sem callback intencionalmente — chamadas frequentes/silenciosas
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
     * Cria um documento de alerta na coleção `alerts/`, vinculado ao cuidador,
     * para que ele receba a notificação em tempo real via snapshot listener.
     */
    public static void enviarAlerta(String uidPaciente, String nomePaciente,
                                     String uidCuidador, int bpm, String tipo,
                                     Callback<String> callback) {
        Map<String, Object> alerta = new HashMap<>();
        alerta.put("pacienteId", uidPaciente);
        alerta.put("pacienteNome", nomePaciente);
        alerta.put("cuidadorId", uidCuidador);
        alerta.put("bpm", bpm);
        alerta.put("tipo", tipo); // "HIGH" ou "LOW"
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
}
