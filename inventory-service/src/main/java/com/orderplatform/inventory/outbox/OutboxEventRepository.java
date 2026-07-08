package com.orderplatform.inventory.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    @Query("select o.id from OutboxEvent o where o.published = false order by o.createdAt asc")
    List<UUID> findUnpublishedIds();

    /**
     * Re-fetches a single row with a pessimistic lock immediately before
     * publishing, so if this service ever scales to multiple instances two
     * pollers can't both send the same event.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OutboxEvent o where o.id = :id")
    Optional<OutboxEvent> findByIdForUpdate(@Param("id") UUID id);
}
