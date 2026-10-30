package com.stockrescue.access.persistence;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class InvitationRateLimitRepository {
    private final JdbcClient jdbc;
    public InvitationRateLimitRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public int recordAttempt(UUID actor, String operation, int seconds, int cap) {
        return jdbc.sql("""
                INSERT INTO stockrescue.access_rate_limit(actor_user_id,operation,window_started_at,attempts)
                VALUES (:actor,:operation,clock_timestamp(),1)
                ON CONFLICT (actor_user_id,operation) DO UPDATE SET
                  attempts=CASE WHEN stockrescue.access_rate_limit.window_started_at <= clock_timestamp()-make_interval(secs=>:seconds)
                    THEN 1 ELSE LEAST(stockrescue.access_rate_limit.attempts+1,:cap) END,
                  window_started_at=CASE WHEN stockrescue.access_rate_limit.window_started_at <= clock_timestamp()-make_interval(secs=>:seconds)
                    THEN clock_timestamp() ELSE stockrescue.access_rate_limit.window_started_at END
                RETURNING attempts
                """).param("actor", actor).param("operation", operation).param("seconds", seconds).param("cap", cap)
                .query(Integer.class).single();
    }
}
