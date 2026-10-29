package com.cvcompass.backend.candidate.service;

import com.cvcompass.backend.candidate.exception.CandidateFailure;
import com.cvcompass.backend.candidate.mapper.CandidateValueMapper;
import com.cvcompass.backend.candidate.repository.CandidateProfileRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Service
public class CandidateExperienceService {
    private final CandidateProfileRepository repository;
    private final CandidateValueMapper mapper;

    public CandidateExperienceService(
            CandidateProfileRepository repository, CandidateValueMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    public UUID insertExperience(
            long id, JsonNode data, String state, String origin, Long doc, Long result) {
        int count = repository.experienceCount(id);
        if (count >= 1000) throw new CandidateFailure(409, "PROFILE_LIMIT");
        UUID key = UUID.randomUUID();
        repository.insertExperience(key, id, data, state, origin, doc, result);
        updateExperience(key, data, state, origin, doc, result);
        return key;
    }

    public void updateExperience(
            UUID key, JsonNode data, String state, String origin, Long doc, Long result) {
        repository.updateExperience(key, data, state, origin, doc, result, mapper.experience(data));
    }
}
