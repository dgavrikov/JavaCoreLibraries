package io.github.dgavrikov.core.inbox.service;

import io.github.dgavrikov.core.inbox.model.InboxEvent;
import io.github.dgavrikov.core.inbox.repository.InboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.BlockingQueue;

@Slf4j
@RequiredArgsConstructor
public class InboxPlatformCoordinator {
    private final BlockingQueue<InboxEvent> inboxMemoryQueue;
    private final InboxRepository inboxRepository;

    public boolean coordinate(InboxEvent event) {
        // 1. Идемпотентный инсерт в БД в текущей бизнес-транзакции транспорта
        boolean isInserted = inboxRepository.saveStrictly(event);

        if (!isInserted) {
            log.debug("Duplicate message detected and skipped at DB level: {}", event.messageId());
            return false;
        }

        // 2. Если мы внутри активной транзакции Spring Spring TX, уходим в in-memory очередь строго ПОСЛЕ коммита
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    offerToMemoryQueue(event);
                }
            });
        } else {
            // Если транзакции нет (например, автокоммит), пушим сразу
            offerToMemoryQueue(event);
        }
        return true;
    }

    private void offerToMemoryQueue(InboxEvent event) {
        boolean queued = inboxMemoryQueue.offer(event);
        if (!queued) {
            // Очередь полна (Backpressure) — не страшно, сообщение останется в БД со статусом NEW и будет поднято Recovery Engine
            log.warn("Inbox In-Memory queue is full. Event {} will be processed later via Recovery Engine.", event.messageId());
        }
    }
}
