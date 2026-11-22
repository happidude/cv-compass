package com.cvcompass.backend.identity.repository;

import com.cvcompass.backend.identity.dto.UserAccount;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {
    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UserAccount> find(String username) {
        return jdbc
                .query(
                        "SELECT \"USERNAME\",\"PASSWORD_HASH\",\"ROLE\",\"ENABLED\" FROM \"IDENTITY\".\"USERS\" WHERE \"USERNAME\"=?",
                        (rs, n) ->
                                new UserAccount(
                                        rs.getString(1),
                                        rs.getString(2),
                                        rs.getString(3),
                                        rs.getBoolean(4)),
                        username)
                .stream()
                .findFirst();
    }

    public void provision(String username, String hash, String role) {
        jdbc.update(
                "INSERT INTO \"IDENTITY\".\"USERS\" (\"USERNAME\",\"PASSWORD_HASH\",\"ROLE\") VALUES (?,?,?) ON CONFLICT (\"USERNAME\") DO NOTHING",
                username,
                hash,
                role);
    }
}
