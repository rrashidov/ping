package com.example.ping.repository;

import com.example.ping.domain.PingResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface PingRepository extends JpaRepository<PingResult, Long> {

    @Modifying
    @Query("DELETE FROM PingResult p WHERE p.pingedAt < :cutoff")
    void deleteByPingedAtBefore(@Param("cutoff") LocalDateTime cutoff);

    List<PingResult> findTop50BySuccessFalseOrderByPingedAtDesc();

    @Query("""
        SELECT p FROM PingResult p
        WHERE p.pingedAt >= :since
        ORDER BY p.pingedAt ASC
        """)
    List<PingResult> findSince(@Param("since") LocalDateTime since);

    @Query("""
        SELECT p FROM PingResult p
        WHERE p.pingedAt >= :since AND p.pingedAt < :until
        ORDER BY p.pingedAt ASC
        """)
    List<PingResult> findBetween(@Param("since") LocalDateTime since,
                                 @Param("until") LocalDateTime until);
}
