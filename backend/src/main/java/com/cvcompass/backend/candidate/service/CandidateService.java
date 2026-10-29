package com.cvcompass.backend.candidate.service;

import com.cvcompass.backend.ingestion.candidateapi.CandidateDocuments;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class CandidateService {
    @Transactional
    public Profile attach(long id, long version, long documentId, String actor) {
        if (!documents.valid(documentId)) throw new CandidateFailure(404, "DOCUMENT_NOT_FOUND");
        documents.lock(documentId);
        expected(id, version);
        var before = detail(id);
        if (!documents.attach(documentId, id))
            throw new CandidateFailure(409, "DOCUMENT_ALREADY_LINKED");
        revision(id, version, actor, "LINK_DOCUMENT", before, detail(id));
        return detail(id);
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final CandidateDocuments documents;

    public CandidateService(JdbcTemplate jdbc, ObjectMapper json, CandidateDocuments documents) {
        this.jdbc = jdbc;
        this.json = json;
        this.documents = documents;
    }

    public record Profile(
            long id,
            long version,
            JsonNode basic,
            String basicState,
            String basicOrigin,
            JsonNode extensions,
            List<Experience> experiences,
            List<CandidateDocuments.Document> documents) {}

    public record Experience(
            UUID id, String state, String origin, Long documentId, Long resultId, JsonNode data) {}

    public record Proposal(UUID id, long documentId, long resultId, JsonNode data) {}

    public record Summary(
            long id, long version, String fullName, String basicState, String importedAt) {}

    public record Page(List<Summary> content, boolean hasNext, Long nextBeforeId) {}

    public record History(
            long id,
            long version,
            String actor,
            String action,
            JsonNode before,
            JsonNode after,
            String at) {}

    public Page list(Long before, int size) {
        if (size < 1 || size > 100 || before != null && before < 1)
            throw new CandidateFailure(400, "VALIDATION_ERROR");
        var rows =
                jdbc.query(
                        """
                SELECT "CANDIDATE_ID","VERSION","FULL_NAME","BASIC_STATE","CREATED_AT"
    FROM "CANDIDATE_PROFILES" WHERE (?::bigint IS NULL OR "CANDIDATE_ID"<?)
    ORDER BY "CANDIDATE_ID" DESC LIMIT ?
    """,
                        (rs, n) ->
                                new Summary(
                                        rs.getLong(1),
                                        rs.getLong(2),
                                        rs.getString(3),
                                        rs.getString(4),
                                        rs.getTimestamp(5).toInstant().toString()),
                        before,
                        before,
                        size + 1);
        boolean next = rows.size() > size;
        var content = List.copyOf(rows.subList(0, Math.min(rows.size(), size)));
        return new Page(content, next, next ? content.getLast().id() : null);
    }

    public Profile detail(long id) {
        var rows =
                jdbc.query(
                        """
   SELECT "VERSION","BASIC"::text,"BASIC_STATE","BASIC_ORIGIN","EXTENSIONS"::text
   FROM "CANDIDATE_PROFILES" WHERE "CANDIDATE_ID"=?
   """,
                        (rs, n) ->
                                new Profile(
                                        id,
                                        rs.getLong(1),
                                        parse(rs.getString(2)),
                                        rs.getString(3),
                                        rs.getString(4),
                                        parse(rs.getString(5)),
                                        experiences(id),
                                        documents.list(id)),
                        id);
        if (rows.isEmpty()) throw new CandidateFailure(404, "CANDIDATE_NOT_FOUND");
        return rows.getFirst();
    }

    private List<Experience> experiences(long id) {
        return jdbc.query(
                """
   SELECT "ID","STATE","ORIGIN","DOCUMENT_ID","RESULT_ID","DATA"::text
   FROM "CANDIDATE_EXPERIENCES" WHERE "CANDIDATE_ID"=? AND NOT "DELETED" ORDER BY "ID"
   """,
                (rs, n) ->
                        new Experience(
                                rs.getObject(1, UUID.class),
                                rs.getString(2),
                                rs.getString(3),
                                (Long) rs.getObject(4),
                                (Long) rs.getObject(5),
                                parse(rs.getString(6))),
                id);
    }

    public List<Proposal> proposals(long id, Long ignored, int size) {
        detailExists(id);
        if (size < 1 || size > 100) throw new CandidateFailure(400, "VALIDATION_ERROR");
        return jdbc.query(
                """
   SELECT "ID","DOCUMENT_ID","RESULT_ID","DATA"::text FROM "CANDIDATE_PROPOSALS"
   WHERE "CANDIDATE_ID"=? AND "STATUS"='OPEN' ORDER BY "RESULT_ID","ID" LIMIT ?
   """,
                (rs, n) ->
                        new Proposal(
                                rs.getObject(1, UUID.class),
                                rs.getLong(2),
                                rs.getLong(3),
                                parse(rs.getString(4))),
                id,
                size);
    }

    public List<History> history(long id, Long before, int size) {
        detailExists(id);
        if (size < 1 || size > 100) throw new CandidateFailure(400, "VALIDATION_ERROR");
        return jdbc.query(
                """
   SELECT "ID","VERSION","ACTOR","ACTION","BEFORE"::text,"AFTER"::text,"CREATED_AT"
   FROM "CANDIDATE_HISTORY" WHERE "CANDIDATE_ID"=? AND (?::bigint IS NULL OR "ID"<?)
   ORDER BY "ID" DESC LIMIT ?
   """,
                (rs, n) ->
                        new History(
                                rs.getLong(1),
                                rs.getLong(2),
                                rs.getString(3),
                                rs.getString(4),
                                parse(rs.getString(5)),
                                parse(rs.getString(6)),
                                rs.getTimestamp(7).toInstant().toString()),
                id,
                before,
                before,
                size);
    }

    private void detailExists(long id) {
        if (!Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM \"CANDIDATE_PROFILES\" WHERE \"CANDIDATE_ID\"=?)",
                        Boolean.class,
                        id))) throw new CandidateFailure(404, "CANDIDATE_NOT_FOUND");
    }

    public long lock(long id) {
        var rows =
                jdbc.queryForList(
                        "SELECT \"VERSION\" FROM \"CANDIDATE_PROFILES\" WHERE \"CANDIDATE_ID\"=? FOR UPDATE",
                        Long.class,
                        id);
        if (rows.isEmpty()) throw new CandidateFailure(404, "CANDIDATE_NOT_FOUND");
        return rows.getFirst();
    }

    private void expected(long id, long version) {
        if (lock(id) != version) throw new CandidateFailure(409, "VERSION_CONFLICT");
    }

    public void revision(
            long id, long version, String actor, String action, Object before, Object after) {
        JsonNode snapshot = after == null ? null : json.valueToTree(after);
        if (snapshot instanceof ObjectNode object) {
            if (object.has("version")) object.put("version", version + 1);
            else if (object.path("profile") instanceof ObjectNode profile)
                profile.put("version", version + 1);
        }
        jdbc.update(
                "UPDATE \"CANDIDATE_PROFILES\" SET \"VERSION\"=\"VERSION\"+1,\"UPDATED_AT\"=clock_timestamp() WHERE \"CANDIDATE_ID\"=?",
                id);
        jdbc.update(
                "INSERT INTO \"CANDIDATE_HISTORY\" (\"CANDIDATE_ID\",\"VERSION\",\"ACTOR\",\"ACTION\",\"BEFORE\",\"AFTER\") VALUES (?,?,?,?,?::jsonb,?::jsonb)",
                id,
                version + 1,
                actor,
                action,
                before == null ? null : json.writeValueAsString(before),
                snapshot == null ? null : snapshot.toString());
    }

    @Transactional
    public Profile basic(long id, long version, JsonNode value, boolean confirm, String actor) {
        expected(id, version);
        Profile before = detail(id);
        if (!confirm) {
            validateBasic(value);
            jdbc.update(
                    "UPDATE \"CANDIDATE_PROFILES\" SET \"BASIC\"=?::jsonb,\"FULL_NAME\"=?,\"EMAIL\"=?,\"PHONE\"=?,\"LOCATION\"=?,\"SUMMARY\"=?,\"BASIC_STATE\"='EDITED',\"BASIC_ORIGIN\"='USER' WHERE \"CANDIDATE_ID\"=?",
                    value.toString(),
                    text(value, "fullName"),
                    text(value, "email"),
                    text(value, "phone"),
                    text(value, "location"),
                    text(value, "summary"),
                    id);
        } else
            jdbc.update(
                    "UPDATE \"CANDIDATE_PROFILES\" SET \"BASIC_STATE\"='CONFIRMED' WHERE \"CANDIDATE_ID\"=?",
                    id);
        revision(id, version, actor, confirm ? "CONFIRM_BASIC" : "EDIT_BASIC", before, detail(id));
        return detail(id);
    }

    @Transactional
    public Profile editExperience(
            long id,
            long version,
            UUID experienceId,
            JsonNode value,
            String operation,
            String actor) {
        expected(id, version);
        var before = detail(id);
        var existing =
                experienceId == null
                        ? null
                        : before.experiences().stream()
                                .filter(e -> e.id().equals(experienceId))
                                .findFirst()
                                .orElseThrow(
                                        () -> new CandidateFailure(404, "EXPERIENCE_NOT_FOUND"));
        switch (operation) {
            case "DELETE" -> {
                if (existing == null) throw new CandidateFailure(400, "VALIDATION_ERROR");
                jdbc.update(
                        "UPDATE \"CANDIDATE_EXPERIENCES\" SET \"DELETED\"=true,\"STATE\"='EDITED',\"ORIGIN\"='USER' WHERE \"ID\"=?",
                        experienceId);
            }
            case "CONFIRM" -> {
                if (existing == null) throw new CandidateFailure(400, "VALIDATION_ERROR");
                jdbc.update(
                        "UPDATE \"CANDIDATE_EXPERIENCES\" SET \"STATE\"='CONFIRMED' WHERE \"ID\"=?",
                        experienceId);
            }
            case "SAVE" -> {
                JsonNode clean = userExperience(value);
                if (existing == null) insertExperience(id, clean, "EDITED", "USER", null, null);
                else updateExperience(existing.id(), clean, "EDITED", "USER", null, null);
            }
            default -> throw new CandidateFailure(400, "VALIDATION_ERROR");
        }
        revision(id, version, actor, operation + "_EXPERIENCE", before, detail(id));
        return detail(id);
    }

    @Transactional
    public Profile decide(
            long id,
            long version,
            UUID proposalId,
            String decision,
            UUID target,
            JsonNode edited,
            String actor) {
        expected(id, version);
        var before = detail(id);
        var rows =
                jdbc.query(
                        """
   SELECT "DOCUMENT_ID","RESULT_ID","DATA"::text FROM "CANDIDATE_PROPOSALS"
   WHERE "ID"=? AND "CANDIDATE_ID"=? AND "STATUS"='OPEN' FOR UPDATE
   """,
                        (rs, n) ->
                                new Proposal(
                                        proposalId,
                                        rs.getLong(1),
                                        rs.getLong(2),
                                        parse(rs.getString(3))),
                        proposalId,
                        id);
        if (rows.isEmpty()) throw new CandidateFailure(409, "PROPOSAL_CLOSED");
        var proposal = rows.getFirst();
        if (!List.of("ACCEPT", "EDIT", "REJECT").contains(decision))
            throw new CandidateFailure(400, "VALIDATION_ERROR");
        if (!"REJECT".equals(decision)) {
            var old =
                    target == null
                            ? null
                            : before.experiences().stream()
                                    .filter(e -> e.id().equals(target))
                                    .findFirst()
                                    .orElseThrow(
                                            () ->
                                                    new CandidateFailure(
                                                            404, "EXPERIENCE_NOT_FOUND"));
            var data = "EDIT".equals(decision) ? userExperience(edited) : proposal.data();
            String origin = "EDIT".equals(decision) ? "USER" : "MAPPING";
            String state = "EDIT".equals(decision) ? "EDITED" : "CONFIRMED";
            if (old == null)
                insertExperience(
                        id,
                        data,
                        state,
                        origin,
                        "EDIT".equals(decision) ? null : proposal.documentId(),
                        "EDIT".equals(decision) ? null : proposal.resultId());
            else
                updateExperience(
                        old.id(),
                        data,
                        state,
                        origin,
                        "EDIT".equals(decision) ? null : proposal.documentId(),
                        "EDIT".equals(decision) ? null : proposal.resultId());
        }
        jdbc.update(
                "UPDATE \"CANDIDATE_PROPOSALS\" SET \"STATUS\"=?,\"DECIDED_BY\"=?,\"DECIDED_AT\"=clock_timestamp() WHERE \"ID\"=?",
                "REJECT".equals(decision) ? "REJECTED" : "ACCEPTED",
                actor,
                proposalId);
        revision(
                id,
                version,
                actor,
                decision + "_PROPOSAL",
                Map.of("profile", before, "proposal", proposal),
                Map.of("profile", detail(id), "proposalId", proposalId, "decision", decision));
        return detail(id);
    }

    public UUID insertExperience(
            long id, JsonNode data, String state, String origin, Long doc, Long result) {
        int count =
                jdbc.queryForObject(
                        "SELECT count(*) FROM \"CANDIDATE_EXPERIENCES\" WHERE \"CANDIDATE_ID\"=?",
                        Integer.class,
                        id);
        if (count >= 1000) throw new CandidateFailure(409, "PROFILE_LIMIT");
        UUID key = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO \"CANDIDATE_EXPERIENCES\" (\"ID\",\"CANDIDATE_ID\",\"DATA\",\"STATE\",\"ORIGIN\",\"DOCUMENT_ID\",\"RESULT_ID\") VALUES (?,?,?::jsonb,?,?,?,?)",
                key,
                id,
                data.toString(),
                state,
                origin,
                doc,
                result);
        updateExperience(key, data, state, origin, doc, result);
        return key;
    }

    private void updateExperience(
            UUID key, JsonNode data, String state, String origin, Long doc, Long result) {
        var company = data.path("company");
        var title = data.path("title");
        var period = data.path("period");
        boolean valid = "PROPOSED".equals(period.path("state").asString());
        jdbc.update(
                """
   UPDATE "CANDIDATE_EXPERIENCES" SET "DATA"=?::jsonb,"STATE"=?,"ORIGIN"=?,"DOCUMENT_ID"=?,"RESULT_ID"=?,
   "COMPANY_RAW"=?,"COMPANY_NORMALIZED"=?,"TITLE_RAW"=?,"START_DATE"=?,"START_PRECISION"=?,"END_DATE"=?,"END_PRECISION"=?,
   "ONGOING_AS_STATED"=?,"FUNCTION_CODES"=?::jsonb,"INDUSTRY_CODES"=?::jsonb,"SENIORITY_CODE"=?,"DESCRIPTION_RAW"=? WHERE "ID"=?
   """,
                data.toString(),
                state,
                origin,
                doc,
                result,
                text(company, "raw"),
                text(company, "normalized"),
                text(title, "raw"),
                valid ? date(period.path("start")) : null,
                valid ? text(period.path("start"), "precision") : null,
                valid ? date(period.path("end")) : null,
                valid ? text(period.path("end"), "precision") : null,
                period.path("ongoingAsStated").asBoolean(),
                array(title, "functionCodes"),
                array(company, "industryCodes"),
                text(title, "seniorityCode"),
                text(data, "descriptionRaw"),
                key);
    }

    private String array(JsonNode node, String field) {
        return node.path(field).isArray() ? node.path(field).toString() : "[]";
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).isString() ? node.path(field).asString() : null;
    }

    private static LocalDate date(JsonNode node) {
        if (node.isNull() || node.isMissingNode()) return null;
        return LocalDate.of(
                node.path("year").asInt(), node.path("month").asInt(1), node.path("day").asInt(1));
    }

    private void validateBasic(JsonNode value) {
        if (value == null
                || !value.isObject()
                || !Set.of("fullName", "email", "phone", "location", "summary")
                        .containsAll(value.propertyNames())) invalid();
        for (String field : value.propertyNames())
            if (!value.get(field).isNull()
                    && (!value.get(field).isString()
                            || value.get(field).asString().length()
                                    > (field.equals("summary")
                                            ? 10000
                                            : field.equals("phone") ? 50 : 255))) invalid();
    }

    private JsonNode userExperience(JsonNode value) {
        if (value == null || !value.isObject() || value.toString().length() > 65536) invalid();
        ObjectNode clean = (ObjectNode) value.deepCopy();
        if (!Set.of(
                        "company",
                        "title",
                        "period",
                        "descriptionRaw",
                        "descriptionEvidence",
                        "evidence",
                        "issues",
                        "localId")
                .containsAll(clean.propertyNames())) invalid();
        stripEvidence(clean);
        for (String field : List.of("company", "title", "period"))
            if (!clean.path(field).isObject()) invalid();
        var companyRaw = clean.path("company").path("raw");
        if (!companyRaw.isString()
                || companyRaw.asString().isBlank()
                || companyRaw.asString().length() > 4096) invalid();
        var titleRaw = clean.path("title").path("raw");
        if (!titleRaw.isMissingNode()
                && !titleRaw.isNull()
                && (!titleRaw.isString() || titleRaw.asString().length() > 4096)) invalid();
        boolean titleFound = titleRaw.isString() && !titleRaw.asString().isBlank();
        ObjectNode company = (ObjectNode) clean.path("company");
        company.put("normalized", company.path("raw").asString().strip().toLowerCase(Locale.ROOT));
        company.put("state", "PROPOSED");
        validateCodes(company.path("industryCodes"));
        validateCodes(clean.path("title").path("functionCodes"));
        ObjectNode title = (ObjectNode) clean.path("title");
        if (!titleFound) title.putNull("raw");
        title.put("state", titleFound ? "PROPOSED" : "NOT_FOUND");
        title.put(
                "functionState",
                title.path("functionCodes").isArray() && !title.path("functionCodes").isEmpty()
                        ? "PROPOSED"
                        : "NOT_FOUND");
        company.put(
                "industryState",
                company.path("industryCodes").isArray() && !company.path("industryCodes").isEmpty()
                        ? "PROPOSED"
                        : "NOT_FOUND");
        if (!clean.path("title").path("seniorityCode").isMissingNode()
                && !clean.path("title").path("seniorityCode").isNull()
                && (!clean.path("title").path("seniorityCode").isString()
                        || !clean.path("title")
                                .path("seniorityCode")
                                .asString()
                                .matches("[A-Z][A-Z0-9_]{0,63}"))) invalid();
        var period = (ObjectNode) clean.path("period");
        try {
            validateDate(period.path("start"));
            validateDate(period.path("end"));
            LocalDate start = date(period.path("start")), end = latestDate(period.path("end"));
            if (start != null && end != null && start.isAfter(end)) invalid();
        } catch (java.time.DateTimeException ex) {
            invalid();
        }
        if (!period.path("ongoingAsStated").isBoolean()) invalid();
        if (period.path("ongoingAsStated").asBoolean()
                && !period.path("end").isNull()
                && !period.path("end").isMissingNode()) invalid();
        period.put("state", period.path("start").isObject() ? "PROPOSED" : "NOT_FOUND");
        if (!clean.path("descriptionRaw").isMissingNode()
                && (!clean.path("descriptionRaw").isString()
                        || clean.path("descriptionRaw").asString().length() > 16384)) invalid();
        company.remove(List.of("industryBasis", "evidence"));
        ((ObjectNode) clean.path("title")).remove(List.of("canonical", "alternatives", "evidence"));
        period.remove(List.of("alternatives", "evidence"));
        clean.remove(List.of("localId", "issues"));
        return clean;
    }

    private void validateDate(JsonNode node) {
        if (node.isNull() || node.isMissingNode()) return;
        if (!node.isObject()
                || !node.path("year").isIntegralNumber()
                || !node.path("year").canConvertToInt()
                || node.path("year").asInt() < 1
                || node.path("year").asInt() > 9999) invalid();
        String p = node.path("precision").asString();
        if (!List.of("YEAR", "MONTH", "DAY").contains(p)) invalid();
        boolean month = !node.path("month").isNull() && !node.path("month").isMissingNode(),
                day = !node.path("day").isNull() && !node.path("day").isMissingNode();
        if (month
                        && (!node.path("month").isIntegralNumber()
                                || !node.path("month").canConvertToInt())
                || day
                        && (!node.path("day").isIntegralNumber()
                                || !node.path("day").canConvertToInt())
                || month != !p.equals("YEAR")
                || day != p.equals("DAY")) invalid();
        date(node);
    }

    private static LocalDate latestDate(JsonNode node) {
        LocalDate first = date(node);
        if (first == null) return null;
        return switch (node.path("precision").asString()) {
            case "YEAR" -> LocalDate.of(first.getYear(), 12, 31);
            case "MONTH" -> java.time.YearMonth.from(first).atEndOfMonth();
            default -> first;
        };
    }

    private void validateCodes(JsonNode codes) {
        if (codes.isMissingNode()) return;
        if (!codes.isArray() || codes.size() > 16) invalid();
        for (var code : codes)
            if (!code.isString() || !code.asString().matches("[A-Z][A-Z0-9_]{0,63}")) invalid();
    }

    private static void stripEvidence(JsonNode node) {
        if (node.isObject()) {
            var object = (ObjectNode) node;
            object.remove(List.of("evidence", "descriptionEvidence"));
            for (String field : new ArrayList<>(object.propertyNames()))
                stripEvidence(object.get(field));
        } else if (node.isArray()) for (var child : node) stripEvidence(child);
    }

    private static void invalid() {
        throw new CandidateFailure(400, "VALIDATION_ERROR");
    }

    private JsonNode parse(String s) {
        return s == null ? null : json.readTree(s);
    }
}
