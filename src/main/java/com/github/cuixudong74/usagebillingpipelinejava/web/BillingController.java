package com.github.cuixudong74.usagebillingpipelinejava.web;

import com.github.cuixudong74.usagebillingpipelinejava.domain.Adjustment;
import com.github.cuixudong74.usagebillingpipelinejava.domain.Bill;
import com.github.cuixudong74.usagebillingpipelinejava.domain.WindowState;
import com.github.cuixudong74.usagebillingpipelinejava.service.BillingService;
import com.github.cuixudong74.usagebillingpipelinejava.web.dto.AdjustmentResponse;
import com.github.cuixudong74.usagebillingpipelinejava.web.dto.BillActionRequest;
import com.github.cuixudong74.usagebillingpipelinejava.web.dto.BillResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/billing")
public class BillingController {

    private final BillingService billingService;

    public BillingController(BillingService billingService) {
        this.billingService = billingService;
    }

    /** 预览出账：只读，不产生已提交版本。 */
    @PostMapping("/preview")
    public ResponseEntity<BillResponse> preview(@Valid @RequestBody BillActionRequest request) {
        Bill bill = billingService.preview(request.accountId(), request.windowId());
        return ResponseEntity.ok(BillResponse.from(bill));
    }

    /** 提交出账：幂等，重复提交不产生第二份账单。 */
    @PostMapping("/commit")
    public ResponseEntity<BillResponse> commit(@Valid @RequestBody BillActionRequest request) {
        Bill bill = billingService.commit(request.accountId(), request.windowId(), "web");
        return ResponseEntity.ok(BillResponse.from(bill));
    }

    /** 更正：显式调整，保留差异。 */
    @PostMapping("/adjust")
    public ResponseEntity<BillResponse> adjust(@Valid @RequestBody BillActionRequest request) {
        Bill bill = billingService.adjust(request.accountId(), request.windowId(),
                request.reason() == null ? "" : request.reason(), "web");
        return ResponseEntity.ok(BillResponse.from(bill));
    }

    /** 封账。 */
    @PostMapping("/seal")
    public ResponseEntity<?> seal(@Valid @RequestBody BillActionRequest request) {
        WindowState ws = billingService.seal(request.accountId(), request.windowId(), "web");
        return ResponseEntity.ok(new SealResponse(ws.getAccountId(), ws.getWindowId(),
                ws.getStatus().name(), ws.getSealedAt()));
    }

    @GetMapping("/bills/{billId}")
    public ResponseEntity<BillResponse> getBill(@PathVariable Long billId) {
        return ResponseEntity.ok(BillResponse.from(billingService.getBill(billId)));
    }

    @GetMapping("/bills/current")
    public ResponseEntity<BillResponse> current(@RequestParam String accountId,
                                                @RequestParam String windowId) {
        return ResponseEntity.ok(BillResponse.from(billingService.getCurrentBill(accountId, windowId)));
    }

    @GetMapping("/bills")
    public ResponseEntity<List<BillResponse>> history(@RequestParam String accountId,
                                                      @RequestParam String windowId) {
        return ResponseEntity.ok(billingService.listHistory(accountId, windowId).stream()
                .map(BillResponse::from).toList());
    }

    @GetMapping("/adjustments")
    public ResponseEntity<List<AdjustmentResponse>> adjustments(@RequestParam String accountId,
                                                                @RequestParam String windowId) {
        return ResponseEntity.ok(billingService.listAdjustments(accountId, windowId).stream()
                .map(AdjustmentResponse::from).toList());
    }

    @GetMapping("/window")
    public ResponseEntity<?> windowState(@RequestParam String accountId,
                                         @RequestParam String windowId) {
        WindowState ws = billingService.getWindowState(accountId, windowId);
        if (ws == null) {
            return ResponseEntity.ok(new SealResponse(accountId, windowId, "OPEN", null));
        }
        return ResponseEntity.ok(new SealResponse(ws.getAccountId(), ws.getWindowId(),
                ws.getStatus().name(), ws.getSealedAt()));
    }

    public record SealResponse(String accountId, String windowId, String status,
                               java.time.Instant sealedAt) {
    }
}
