package com.cityapp.outbox.service;
import com.cityapp.outbox.entity.*;
import com.cityapp.outbox.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;
@Service @RequiredArgsConstructor
public class OutboxClaimService {
 private final OutboxEventRepository repository;
 @Transactional public List<OutboxEvent> claim(int batchSize){
  List<OutboxEvent> events=repository.lockPendingEvents(Instant.now(),batchSize);
  Instant lockedAt=Instant.now();
  events.forEach(e->{e.setStatus(OutboxStatus.PROCESSING);e.setLockedAt(lockedAt);e.setAttempts(e.getAttempts()+1);});
  return repository.saveAll(events);
 }
 @Transactional public int recoverStaleEvents(long staleSeconds){
  return repository.recoverStaleEvents(OutboxStatus.PROCESSING,OutboxStatus.PENDING,Instant.now().minusSeconds(staleSeconds));
 }
 @Transactional public void markSent(Long id){repository.findById(id).ifPresent(e->{e.setStatus(OutboxStatus.SENT);e.setSentAt(Instant.now());e.setLockedAt(null);e.setLastError(null);});}
 @Transactional public void markFailed(Long id,String error,long retrySeconds){repository.findById(id).ifPresent(e->{e.setStatus(OutboxStatus.PENDING);e.setAvailableAt(Instant.now().plusSeconds(retrySeconds));e.setLockedAt(null);e.setLastError(error);});}
}