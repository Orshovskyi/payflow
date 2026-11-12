package com.payflow.chaos;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Registered only when {@code payflow.chaos.enabled=true}; called by the experiment script. */
@RestController
public class ChaosController {

    private final CrashPoint crashPoint;

    public ChaosController(CrashPoint crashPoint) {
        this.crashPoint = crashPoint;
    }

    @PostMapping("/chaos/crash-after-commit")
    public ResponseEntity<Void> armCrashAfterCommit() {
        crashPoint.arm();
        return ResponseEntity.accepted().build();
    }
}
