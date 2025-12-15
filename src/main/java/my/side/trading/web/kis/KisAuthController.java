package my.side.trading.web.kis;

import lombok.RequiredArgsConstructor;
import my.side.trading.kis.client.KisAuthService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class KisAuthController {

    private final KisAuthService kisAuthService;

    @GetMapping("/kis/token")
    public String getToken() {
        return kisAuthService.getAccessToken();
    }

    @GetMapping("/kis/approval-key")
    public String getApprovalKey() {
        return kisAuthService.issueApprovalKey();
    }
}
