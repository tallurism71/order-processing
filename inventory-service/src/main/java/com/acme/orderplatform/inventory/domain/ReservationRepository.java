package com.acme.orderplatform.inventory.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {
    List<Reservation> findByOrderIdAndStatus(UUID orderId, Reservation.Status status);
}
