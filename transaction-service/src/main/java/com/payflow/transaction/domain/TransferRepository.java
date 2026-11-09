package com.payflow.transaction.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {

    long countByStatus(TransferStatus status);
}
