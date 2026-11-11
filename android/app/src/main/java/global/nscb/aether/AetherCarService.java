package global.nscb.aether;

import androidx.annotation.NonNull;
import androidx.car.app.CarAppService;
import androidx.car.app.Session;
import androidx.car.app.validation.HostValidator;

public final class AetherCarService extends CarAppService {
    @NonNull
    @Override
    public HostValidator createHostValidator() {
        // Required for sideload / unknown sources testing on phone + head unit hosts
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR;
    }

    @NonNull
    @Override
    public Session onCreateSession() {
        return new AetherSession();
    }
}
