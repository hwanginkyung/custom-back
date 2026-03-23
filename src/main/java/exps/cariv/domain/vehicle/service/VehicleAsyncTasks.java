package exps.cariv.domain.vehicle.service;

import exps.cariv.domain.malso.print.MalsoPrintService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class VehicleAsyncTasks {

    private final MalsoPrintService malsoPrintService;

    @Async
    public void preGenerateMalsoDocs(Long companyId, Long vehicleId) {
        try {
            malsoPrintService.prepareItems(companyId, vehicleId);
        } catch (Exception e) {
            log.warn("[VehicleAsyncTasks] failed to pre-generate malso docs vehicleId={}", vehicleId, e);
        }
    }
}
