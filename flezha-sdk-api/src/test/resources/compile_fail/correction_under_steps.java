// topology.unknown_field: `correction_ms` is a setting of `effectively_once`; a steps run has none.
package nowhere;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operator.Operator;

final class Misplaced implements Operator {
    @Override
    public String factoryId() {
        return "correction_under_steps";
    }

    @Override
    public void channels(ChannelBuilder c) {}

    @Override
    public Run run() {
        return new Run.Steps(Each.ONCE, OnFailure.UNDO_ALL).withCorrectionMs(5_000);
    }
}
