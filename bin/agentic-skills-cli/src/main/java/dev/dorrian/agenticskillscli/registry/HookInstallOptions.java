package dev.dorrian.agenticskillscli.registry;

import java.util.ArrayList;
import java.util.List;

/**
 * Which opt-in hooks to install on top of the always-on analytics set. Each one can change how the
 * host tool behaves (guard and verify can block; context adds text to the session), so each is a
 * separate, default-off choice.
 */
public record HookInstallOptions(boolean guard, boolean verify, boolean context) {

    public static final HookInstallOptions NONE = new HookInstallOptions(false, false, false);

    /** The opt-in descriptors this selection enables, in registration order. */
    public List<HookDescriptor> descriptors() {
        List<HookDescriptor> out = new ArrayList<>();
        if (guard) out.add(HooksRegistry.GUARD);
        if (verify) out.add(HooksRegistry.VERIFY);
        if (context) out.add(HooksRegistry.CONTEXT);
        return out;
    }

    public boolean any() {
        return guard || verify || context;
    }
}
