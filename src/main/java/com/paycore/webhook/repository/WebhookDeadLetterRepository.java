package com.paycore.webhook.repository;

import com.paycore.webhook.domain.WebhookDeadLetter;
import com.paycore.webhook.domain.WebhookDeadLetterStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WebhookDeadLetterRepository extends JpaRepository<WebhookDeadLetter, Long> {

    List<WebhookDeadLetter> findByStatusOrderByCreatedAtAsc(WebhookDeadLetterStatus status);
}
