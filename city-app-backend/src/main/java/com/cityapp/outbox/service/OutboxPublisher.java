package com.cityapp.outbox.service;
import com.cityapp.outbox.entity.OutboxEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.List;
@Slf4j @Component @RequiredArgsConstructor
public class OutboxPublisher {
 private final OutboxClaimService claimService;
 private final KafkaTemplate<String,Object> kafkaTemplate;
 private final ObjectMapper objectMapper;
 @Scheduled(fixedDelayString="${cityapp.outbox.poll-interval-ms:5000}")
 public void publishPending(){for(OutboxEvent e:claimService.claim(100)) publish(e);}
 @Scheduled(fixedDelayString="${cityapp.outbox.recovery-interval-ms:30000}")
 public void recoverStale(){int n=claimService.recoverStaleEvents(120);if(n>0)log.warn("Recovered {} stale outbox events",n);}
 private void publish(OutboxEvent e){
  try{
   Class<?> type=Class.forName(e.getEventType());
   Object payload=objectMapper.readValue(e.getPayload(),type);
   kafkaTemplate.send(e.getTopic(),e.getMessageKey(),payload).whenComplete((result,ex)->{
    if(ex==null){claimService.markSent(e.getId());log.debug("Outbox published id={} eventId={} topic={}",e.getId(),e.getEventId(),e.getTopic());}
    else markFailed(e,ex);
   });
  }catch(Exception ex){markFailed(e,ex);}
 }
 private void markFailed(OutboxEvent e,Throwable ex){
  long retrySeconds=Math.min(300L,1L<<Math.min(e.getAttempts(),8));
  claimService.markFailed(e.getId(),truncate(ex.getMessage()),retrySeconds);
  log.error("Outbox publish failed id={} eventId={} topic={} attempts={}",e.getId(),e.getEventId(),e.getTopic(),e.getAttempts(),ex);
 }
 private String truncate(String s){if(s==null)return "unknown Kafka publish failure";return s.length()>2000?s.substring(0,2000):s;}
}