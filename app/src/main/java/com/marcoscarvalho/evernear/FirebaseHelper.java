package com.marcoscarvalho.evernear;

import android.util.Log;

import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Centraliza as operações do Firestore para o EverNear.
 *
 * ┌─ Atomicidade ───────────────────────────────────────────────────────────────┐
 * │  Toda operação que modifica MAIS DE UM documento usa Firestore Transaction  │
 * │  ou WriteBatch para garantir consistência. Nunca duas operações sequenciais  │
 * │  independentes onde a segunda pode falhar após a primeira já ter escrito.    │
 * └────────────────────────────────────────────────────────────────────────────┘
 *
 * ┌─ Schema Firestore ──────────────────────────────────────────────────────────┐
 * │  users/{uid}                                                                 │
 * │    nome, email, tipo, apelido, telefone?                                     │
 * │    [paciente] codigoVinculo, cuidadoresVinculados[], bpmBaseline, bpmMin,   │
 * │               bpmMax, ultimoBpm, ultimaBpmTimestamp, ultimaAtualizacao,     │
 * │               statusMonitoramento, bateriaSmartwatch                         │
 * │    [cuidador] pacientesVinculados[]                                          │
 * │                                                                              │
 * │  alerts/{id}                                                                 │
 * │    pacienteId, pacienteNome, cuidadorId, bpm, bpmMin, bpmMax, tipo,         │
 * │    prioridade, timestamp, acknowledged, acknowledgedBy?, acknowledgedAt?    │
 * └────────────────────────────────────────────────────────────────────────────┘
 */
public class FirebaseHelper {

    private static final String TAG = "FirebaseHelper";

    private static final FirebaseFirestore db = FirebaseFirestore.getInstance();

    // Limites de vinculação
    private static final int MAX_CUIDADORES_POR_PACIENTE = 3;
    private static final int MAX_PACIENTES_POR_CUIDADOR  = 3;

    // Tentativas máximas para geração de código único
    private static final int MAX_TENTATIVAS_CODIGO = 5;

    // ==================== Constantes de campos Firestore ====================

    /**
     * Nomes dos campos usados no Firestore — centraliza as strings literais para evitar
     * erros de digitação e facilitar renomeações futuras.
     */
    public static final class Fields {
        // users/{uid}
        public static final String NOME                  = "nome";
        public static final String EMAIL                 = "email";
        public static final String TIPO                  = "tipo";
        public static final String APELIDO               = "apelido";
        public static final String TELEFONE              = "telefone";
        public static final String CODIGO_VINCULO        = "codigoVinculo";
        public static final String CUIDADORES_VINCULADOS = "cuidadoresVinculados";
        public static final String PACIENTES_VINCULADOS  = "pacientesVinculados";
        public static final String ULTIMO_BPM            = "ultimoBpm";
        public static final String ULTIMO_BPM_TIMESTAMP  = "ultimoBpmTimestamp";
        public static final String ULTIMA_ATUALIZACAO    = "ultimaAtualizacao";
        public static final String BPM_BASELINE          = "bpmBaseline";
        public static final String BPM_MIN               = "bpmMin";
        public static final String BPM_MAX               = "bpmMax";
        public static final String STATUS_MONITORAMENTO  = "statusMonitoramento";
        public static final String BATERIA_SMARTWATCH    = "bateriaSmartwatch";
        // alerts/{id}
        public static final String PACIENTE_ID           = "pacienteId";
        public static final String PACIENTE_NOME         = "pacienteNome";
        public static final String CUIDADOR_ID           = "cuidadorId";
        public static final String BPM                   = "bpm";
        public static final String TIPO_ALERTA           = "tipo";
        public static final String PRIORIDADE            = "prioridade";
        public static final String TIMESTAMP             = "timestamp";
        public static final String ACKNOWLEDGED          = "acknowledged";
        public static final String ACKNOWLEDGED_BY       = "acknowledgedBy";
        public static final String ACKNOWLEDGED_AT       = "acknowledgedAt";

        private Fields() {}
    }

    // ==================== Constantes de tipos de usuário ====================

    /**
     * Valores do campo {@code tipo} no Firestore — suporta PT e EN para
     * compatibilidade com registros antigos.
     */
    public static final class Tipos {
        public static final String PACIENTE  = "paciente";
        public static final String PATIENT   = "patient";
        public static final String CUIDADOR  = "cuidador";
        public static final String CAREGIVER = "caregiver";
        private Tipos() {}
    }

    // ==================== Helpers de domínio ====================

    /**
     * @return {@code true} se {@code tipo} representa um paciente (PT ou EN).
     * Centraliza a verificação bilíngue que antes se repetia em 5 arquivos.
     */
    public static boolean isPaciente(String tipo) {
        return Tipos.PACIENTE.equals(tipo) || Tipos.PATIENT.equals(tipo);
    }

    /**
     * @return {@code true} se {@code tipo} representa um cuidador (PT ou EN).
     */
    public static boolean isCuidador(String tipo) {
        return Tipos.CUIDADOR.equals(tipo) || Tipos.CAREGIVER.equals(tipo);
    }

    /**
     * Retorna o nome de exibição preferido: apelido se definido, depois nome, depois fallback.
     *
     * Centraliza o padrão {@code (apelido != null && !apelido.isEmpty()) ? apelido : nome}
     * que antes se repetia em quatro Activities.
     *
     * @param apelido  apelido personalizado do usuário (pode ser null)
     * @param nome     nome completo do usuário (pode ser null)
     * @param fallback texto retornado quando ambos são null ou vazios
     */
    public static String nomeExibir(String apelido, String nome, String fallback) {
        if (apelido != null && !apelido.isEmpty()) return apelido;
        if (nome    != null && !nome.isEmpty())    return nome;
        return fallback;
    }

    /** Sobrecarga com fallback padrão "Usuário". */
    public static String nomeExibir(String apelido, String nome) {
        return nomeExibir(apelido, nome, "Usuário");
    }

    // ==================== Interface de callback ====================

    public interface Callback<T> {
        void onResult(T result);
        void onError(Exception e);
    }

    // ==================== Código de vínculo ====================

    /**
     * Gera um código de vínculo aleatório de 6 caracteres alfanuméricos.
     * Usa SecureRandom para melhor distribuição e menor probabilidade de colisão.
     * Espaço de 36^6 ≈ 2,17 bilhões de combinações.
     */
    public static String gerarCodigoVinculo() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        SecureRandom rnd    = new SecureRandom();
        StringBuilder codigo = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            codigo.append(chars.charAt(rnd.nextInt(chars.length())));
        }
        return codigo.toString();
    }

    /**
     * Garante que o paciente tenha um código de vínculo único no Firestore.
     *
     * Fluxo:
     *  1. Lê o documento — se já tem código, retorna sem write.
     *  2. Gera código e verifica unicidade na coleção.
     *  3. Se houver colisão, tenta novamente (até MAX_TENTATIVAS_CODIGO).
     *  4. Se único, salva e retorna.
     */
    public static void garantirCodigoUnico(String uid, Callback<String> callback) {
        db.collection("users").document(uid).get()
                .addOnSuccessListener(doc -> {
                    if (!doc.exists()) {
                        callback.onError(new Exception("Documento do usuário não encontrado"));
                        return;
                    }
                    String existente = doc.getString(Fields.CODIGO_VINCULO);
                    if (existente != null && !existente.isEmpty()) {
                        callback.onResult(existente); // já tem código — sem write
                        return;
                    }
                    gerarESalvarCodigoUnico(uid, 0, callback);
                })
                .addOnFailureListener(callback::onError);
    }

    private static void gerarESalvarCodigoUnico(String uid, int tentativa,
                                                 Callback<String> callback) {
        if (tentativa >= MAX_TENTATIVAS_CODIGO) {
            callback.onError(new Exception(
                    "Não foi possível gerar código único após " + MAX_TENTATIVAS_CODIGO + " tentativas"));
            return;
        }
        String novoCodigo = gerarCodigoVinculo();
        db.collection("users")
                .whereEqualTo(Fields.CODIGO_VINCULO, novoCodigo)
                .get()
                .addOnSuccessListener(qs -> {
                    if (!qs.isEmpty()) {
                        Log.w(TAG, "Colisão de código (tentativa " + tentativa + ") — retentando");
                        gerarESalvarCodigoUnico(uid, tentativa + 1, callback);
                        return;
                    }
                    db.collection("users").document(uid)
                            .update(Fields.CODIGO_VINCULO, novoCodigo)
                            .addOnSuccessListener(v -> {
                                Log.d(TAG, "Código único gerado: " + novoCodigo);
                                callback.onResult(novoCodigo);
                            })
                            .addOnFailureListener(callback::onError);
                })
                .addOnFailureListener(callback::onError);
    }

    // ==================== Usuário ====================

    /**
     * Cria o documento do usuário no Firestore.
     *
     * Para pacientes: gera código de vínculo localmente (probabilidade de colisão
     * baixíssima no cadastro; colisões são resolvidas posteriormente via
     * {@link #garantirCodigoUnico} no DashboardPacienteActivity).
     *
     * @param telefone número do paciente — null para cuidadores
     */
    public static void salvarUsuario(String uid, String nome, String email,
                                      String tipo, String telefone,
                                      Callback<String> callback) {
        Map<String, Object> user = new HashMap<>();
        user.put(Fields.NOME,  nome);
        user.put(Fields.EMAIL, email);
        user.put(Fields.TIPO,  tipo);
        if (telefone != null && !telefone.isEmpty()) {
            user.put(Fields.TELEFONE, telefone);
        }

        String codigoGerado;
        if (isPaciente(tipo)) {
            codigoGerado = gerarCodigoVinculo();
            user.put(Fields.CODIGO_VINCULO,        codigoGerado);
            user.put(Fields.CUIDADORES_VINCULADOS, new ArrayList<String>());
        } else {
            codigoGerado = null;
            user.put(Fields.PACIENTES_VINCULADOS, new ArrayList<String>());
        }

        final String codigoFinal = codigoGerado;
        db.collection("users").document(uid)
                .set(user)
                .addOnSuccessListener(aVoid -> callback.onResult(codigoFinal))
                .addOnFailureListener(callback::onError);
    }

    public static void salvarApelido(String uid, String apelido, Callback<Void> callback) {
        db.collection("users").document(uid)
                .update(Fields.APELIDO, apelido)
                .addOnSuccessListener(aVoid -> callback.onResult(null))
                .addOnFailureListener(callback::onError);
    }

    public static void buscarPacientePorCodigo(String codigo, Callback<DocumentSnapshot> callback) {
        db.collection("users")
                .whereEqualTo(Fields.CODIGO_VINCULO, codigo)
                .get()
                .addOnSuccessListener(qs -> {
                    if (!qs.isEmpty()) callback.onResult(qs.getDocuments().get(0));
                    else callback.onResult(null);
                })
                .addOnFailureListener(callback::onError);
    }

    // ==================== Vinculação ====================

    /**
     * Vincula cuidador ↔ paciente de forma ATÔMICA via transação Firestore.
     *
     * A transação garante que:
     *  1. Os limites máximos são verificados e as escritas ocorrem atomicamente.
     *  2. Dois cuidadores tentando vincular ao mesmo paciente simultaneamente
     *     não causam estado inconsistente — um deles recebe ABORTED.
     *  3. Se qualquer escrita falhar, nenhuma ocorre (sem estado parcial).
     *
     * Erros semânticos no callback.onError().getMessage():
     *  "LIMITE_CUIDADOR" — cuidador já possui MAX_PACIENTES_POR_CUIDADOR pacientes
     *  "LIMITE_PACIENTE" — paciente já possui MAX_CUIDADORES_POR_PACIENTE cuidadores
     *  "JA_VINCULADO"   — este cuidador já está na lista do paciente
     *
     * @param uidCuidador UID do cuidador que está se vinculando
     * @param uidPaciente UID do paciente encontrado pelo código
     */
    public static void vincularPacienteCuidador(String uidCuidador, String uidPaciente,
                                                 Callback<Void> callback) {
        DocumentReference refCuidador = db.collection("users").document(uidCuidador);
        DocumentReference refPaciente = db.collection("users").document(uidPaciente);

        db.runTransaction(transaction -> {
            DocumentSnapshot snapCuidador = transaction.get(refCuidador);
            DocumentSnapshot snapPaciente = transaction.get(refPaciente);

            @SuppressWarnings("unchecked")
            List<String> pacsCuidador  = (List<String>) snapCuidador.get(Fields.PACIENTES_VINCULADOS);
            @SuppressWarnings("unchecked")
            List<String> cuidsPaciente = (List<String>) snapPaciente.get(Fields.CUIDADORES_VINCULADOS);

            int totalPacs  = pacsCuidador  != null ? pacsCuidador.size()  : 0;
            int totalCuids = cuidsPaciente != null ? cuidsPaciente.size() : 0;

            if (totalPacs >= MAX_PACIENTES_POR_CUIDADOR) {
                throw new FirebaseFirestoreException(
                        "LIMITE_CUIDADOR", FirebaseFirestoreException.Code.ABORTED);
            }
            if (totalCuids >= MAX_CUIDADORES_POR_PACIENTE) {
                throw new FirebaseFirestoreException(
                        "LIMITE_PACIENTE", FirebaseFirestoreException.Code.ABORTED);
            }
            if (cuidsPaciente != null && cuidsPaciente.contains(uidCuidador)) {
                throw new FirebaseFirestoreException(
                        "JA_VINCULADO", FirebaseFirestoreException.Code.ABORTED);
            }

            // arrayUnion é idempotente e seguro mesmo se o campo ainda não existir
            transaction.update(refCuidador, Fields.PACIENTES_VINCULADOS,
                    FieldValue.arrayUnion(uidPaciente));
            transaction.update(refPaciente, Fields.CUIDADORES_VINCULADOS,
                    FieldValue.arrayUnion(uidCuidador));
            return null;
        })
        .addOnSuccessListener(unused -> {
            Log.d(TAG, "Vinculação concluída: cuidador=" + uidCuidador
                    + " paciente=" + uidPaciente);
            callback.onResult(null);
        })
        .addOnFailureListener(e -> {
            Log.w(TAG, "Falha na vinculação: " + e.getMessage());
            callback.onError(e);
        });
    }

    // ==================== Monitoramento ====================

    /**
     * Atualiza o BPM atual do paciente no Firestore.
     *
     * Throttled pelo chamador (HeartRateService — intervalo mínimo de 10 s).
     * Sem callback: chamado com alta frequência; falhas são descartadas silenciosamente
     * pois a próxima atualização substituirá o valor em segundos.
     *
     * Campos escritos:
     *  - ultimoBpm           — valor inteiro do BPM
     *  - ultimoBpmTimestamp  — timestamp do servidor (para sincronização cross-device)
     *  - ultimaAtualizacao   — millis do cliente (para cálculo de "há X min" offline)
     */
    public static void atualizarBpm(String uidPaciente, int bpm) {
        Map<String, Object> updates = new HashMap<>();
        updates.put(Fields.ULTIMO_BPM,           bpm);
        updates.put(Fields.ULTIMO_BPM_TIMESTAMP,  FieldValue.serverTimestamp());
        updates.put(Fields.ULTIMA_ATUALIZACAO,    System.currentTimeMillis());
        db.collection("users").document(uidPaciente).update(updates);
    }

    /**
     * Atualiza os limites de calibração do paciente.
     * Chamado UMA VEZ após a calibração — evento crítico.
     * Tem callback para confirmar persistência (diferente de atualizarBpm).
     *
     * @param callback pode ser null se o chamador não precisar de confirmação
     */
    public static void salvarBaseline(String uidPaciente, int baseline, int min, int max,
                                       Callback<Void> callback) {
        Map<String, Object> updates = new HashMap<>();
        updates.put(Fields.BPM_BASELINE, baseline);
        updates.put(Fields.BPM_MIN,      min);
        updates.put(Fields.BPM_MAX,      max);
        db.collection("users").document(uidPaciente).update(updates)
                .addOnSuccessListener(v -> {
                    Log.d(TAG, "Baseline salvo: baseline=" + baseline
                            + " min=" + min + " max=" + max);
                    if (callback != null) callback.onResult(null);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Falha ao salvar baseline: " + e.getMessage());
                    if (callback != null) callback.onError(e);
                });
    }

    /** Sobrecarga sem callback para chamadores que não precisam de confirmação. */
    public static void salvarBaseline(String uidPaciente, int baseline, int min, int max) {
        salvarBaseline(uidPaciente, baseline, min, max, null);
    }

    /**
     * Atualiza o status do monitoramento cardíaco no perfil do paciente.
     *
     * Valores definidos:
     *  "ATIVO"        — HeartRateService rodando, sensor respondendo
     *  "RECONECTANDO" — watchdog tentando recuperar o sensor
     *  "PARADO"       — serviço encerrado normalmente
     *  "SEM_SENSOR"   — sensor cardíaco indisponível neste dispositivo
     *
     * Sem callback: chamado em transições de estado; falha silenciosa é aceitável
     * pois o campo é informativo (não compromete a segurança do paciente).
     */
    public static void salvarStatusMonitoramento(String uidPaciente, String status) {
        db.collection("users").document(uidPaciente)
                .update(Fields.STATUS_MONITORAMENTO, status);
        Log.d(TAG, "Status monitoramento: " + status + " (uid=" + uidPaciente + ")");
    }

    /**
     * Registra o nível de bateria do smartwatch no perfil do paciente.
     *
     * Exibido no DashboardPacienteActivity para que o paciente saiba quando
     * carregar o relógio antes que o monitoramento seja interrompido.
     *
     * @param nivel 0–100; usar {@code android.os.BatteryManager.EXTRA_LEVEL}
     */
    public static void salvarBateriaSmartwatch(String uidPaciente, int nivel) {
        db.collection("users").document(uidPaciente)
                .update(Fields.BATERIA_SMARTWATCH, nivel);
    }

    // ==================== Alertas ====================

    /**
     * Cria um documento de alerta na coleção {@code alerts/}.
     *
     * Inclui bpmMin/bpmMax no documento para que o CaregiverAlertService possa
     * exibir os limites na notificação sem read adicional ao perfil do paciente.
     *
     * @param uidPaciente  UID do paciente que gerou o alerta
     * @param nomePaciente nome do paciente (desnormalizado para exibição rápida)
     * @param uidCuidador  UID do cuidador que receberá a notificação
     * @param bpm          BPM no momento da anomalia
     * @param tipo         "HIGH", "LOW" ou "MANUAL"
     * @param prioridade   posição do cuidador na cadeia de escalada (0=primeiro)
     * @param bpmMin       limite mínimo configurado; -1 se desconhecido
     * @param bpmMax       limite máximo configurado; -1 se desconhecido
     * @param callback     retorna o ID do documento criado; usado pela lógica de escalada
     */
    public static void enviarAlerta(String uidPaciente, String nomePaciente,
                                     String uidCuidador, int bpm, String tipo,
                                     int prioridade, int bpmMin, int bpmMax,
                                     Callback<String> callback) {
        Map<String, Object> alerta = new HashMap<>();
        alerta.put(Fields.PACIENTE_ID,   uidPaciente);
        alerta.put(Fields.PACIENTE_NOME, nomePaciente);
        alerta.put(Fields.CUIDADOR_ID,   uidCuidador);
        alerta.put(Fields.BPM,           bpm);
        alerta.put(Fields.TIPO_ALERTA,   tipo);
        alerta.put(Fields.PRIORIDADE,    prioridade);
        alerta.put(Fields.TIMESTAMP,     FieldValue.serverTimestamp());
        alerta.put(Fields.ACKNOWLEDGED,  false);
        // Limites desnormalizados: CaregiverAlertService exibe sem read extra
        if (bpmMin > 0) alerta.put(Fields.BPM_MIN, bpmMin);
        if (bpmMax > 0) alerta.put(Fields.BPM_MAX, bpmMax);

        db.collection("alerts").add(alerta)
                .addOnSuccessListener(docRef -> {
                    Log.d(TAG, "Alerta criado: id=" + docRef.getId()
                            + " tipo=" + tipo + " bpm=" + bpm
                            + " cuidador=" + uidCuidador);
                    if (callback != null) callback.onResult(docRef.getId());
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Falha ao criar alerta: " + e.getMessage());
                    if (callback != null) callback.onError(e);
                });
    }

    /**
     * Sobrecarga sem limites de BPM — para chamadores que não têm esses valores
     * disponíveis (ex.: emergência manual acionada antes da calibração).
     */
    public static void enviarAlerta(String uidPaciente, String nomePaciente,
                                     String uidCuidador, int bpm, String tipo,
                                     int prioridade, Callback<String> callback) {
        enviarAlerta(uidPaciente, nomePaciente, uidCuidador, bpm, tipo,
                prioridade, -1, -1, callback);
    }

    /** Sobrecarga para emergência manual: prioridade 0, sem limites de BPM. */
    public static void enviarAlerta(String uidPaciente, String nomePaciente,
                                     String uidCuidador, int bpm, String tipo,
                                     Callback<String> callback) {
        enviarAlerta(uidPaciente, nomePaciente, uidCuidador, bpm, tipo,
                0, -1, -1, callback);
    }

    /**
     * Verifica se um alerta ainda não foi confirmado.
     * Usado pela lógica de escalada para decidir se deve notificar o próximo cuidador.
     */
    public static void alertaFoiConfirmado(String alertaId, Callback<Boolean> callback) {
        db.collection("alerts").document(alertaId).get()
                .addOnSuccessListener(doc -> {
                    if (!doc.exists()) {
                        callback.onResult(true); // documento removido = confirmado
                        return;
                    }
                    Boolean acknowledged = doc.getBoolean(Fields.ACKNOWLEDGED);
                    callback.onResult(Boolean.TRUE.equals(acknowledged));
                })
                .addOnFailureListener(e -> callback.onResult(false));
    }

    /**
     * Confirma um alerta com auditoria completa.
     *
     * Campos escritos:
     *  - acknowledged    = true
     *  - acknowledgedBy  = UID do cuidador que confirmou
     *  - acknowledgedAt  = timestamp do servidor (para cálculo de tempo de resposta)
     *
     * @param alertaId    ID do documento de alerta
     * @param uidCuidador UID do cuidador que está confirmando
     * @param callback    onResult(null) em caso de sucesso
     */
    public static void confirmarAlerta(String alertaId, String uidCuidador,
                                        Callback<Void> callback) {
        Map<String, Object> updates = new HashMap<>();
        updates.put(Fields.ACKNOWLEDGED,    true);
        updates.put(Fields.ACKNOWLEDGED_BY, uidCuidador);
        updates.put(Fields.ACKNOWLEDGED_AT, FieldValue.serverTimestamp());

        db.collection("alerts").document(alertaId).update(updates)
                .addOnSuccessListener(v -> {
                    Log.d(TAG, "Alerta " + alertaId + " confirmado por " + uidCuidador);
                    if (callback != null) callback.onResult(null);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Falha ao confirmar alerta " + alertaId + ": " + e.getMessage());
                    if (callback != null) callback.onError(e);
                });
    }
}
