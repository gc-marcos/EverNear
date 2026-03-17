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
     */
    public static void salvarUsuario(String uid, String nome, String email, String tipo, Callback<String> callback) {
        Map<String, Object> user = new HashMap<>();
        user.put("nome", nome);
        user.put("email", email);
        user.put("tipo", tipo);

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
}
