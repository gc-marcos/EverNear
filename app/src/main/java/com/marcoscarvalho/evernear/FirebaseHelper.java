package com.marcoscarvalho.evernear;

import com.google.android.gms.tasks.Task;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QuerySnapshot;

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

    public static void salvarUsuario(String uid, String nome, String email, String tipo, Callback<Void> callback) {
        Map<String, Object> user = new HashMap<>();
        user.put("nome", nome);
        user.put("email", email);
        user.put("tipo", tipo);

        if ("paciente".equals(tipo)) {
            user.put("codigoVinculo", gerarCodigoVinculo());
            user.put("cuidadorVinculado", null);
        } else {
            user.put("pacientesVinculados", new java.util.ArrayList<String>());
        }

        db.collection("users").document(uid)
                .set(user)
                .addOnSuccessListener(aVoid -> callback.onResult(null))
                .addOnFailureListener(callback::onError);
    }

    public static void buscarPacientePorCodigo(String codigo, Callback<DocumentSnapshot> callback) {
        db.collection("users")
                .whereEqualTo("tipo", "paciente")
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
