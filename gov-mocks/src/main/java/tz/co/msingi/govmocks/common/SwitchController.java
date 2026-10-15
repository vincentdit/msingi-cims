package tz.co.msingi.govmocks.common;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Developer endpoints: which systems are in a simulated outage. */
@RestController
public class SwitchController {

    private final MockSwitches switches;

    public SwitchController(MockSwitches switches) {
        this.switches = switches;
    }

    @GetMapping("/mock/status")
    public Map<String, Boolean> status() {
        return switches.all();
    }

    @PostMapping("/mock/outage")
    public ResponseEntity<?> outage(@RequestParam String system, @RequestParam boolean on) {
        try {
            switches.set(system.toUpperCase(), on);
            return ResponseEntity.ok(switches.all());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
