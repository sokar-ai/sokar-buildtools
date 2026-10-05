package org.fuin.sokar.machines;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link LocalVms}, without a hypervisor.
 */
class LocalVmsTest {

    /** What virsh actually printed for the machine this was written against. */
    private static final String DOMIFADDR = """
             Name       MAC address          Protocol     Address
            -------------------------------------------------------------------------------
             vnet3      52:54:00:1a:2b:3c    ipv4         192.168.122.174/24
            """;

    @Test
    void readsTheAddressOutOfPaddedColumns() {
        // Padded rather than delimited, with a header and a rule above it: counting fields would
        // read the rule as a row.
        assertThat(LocalVms.address(DOMIFADDR)).isEqualTo("192.168.122.174");
    }

    @Test
    void hasNoAddressYetWhileTheMachineIsStillBooting() {
        // virsh prints the header and nothing under it, which is not an error and not an address.
        assertThat(LocalVms.address("""
             Name       MAC address          Protocol     Address
            -------------------------------------------------------------------------------
            """)).isNull();
        assertThat(LocalVms.address("")).isNull();
    }

    @Test
    void takesTheFirstAddressWhenAMachineHasTwoInterfaces() {
        assertThat(LocalVms.address(DOMIFADDR
                + " vnet4      52:54:00:9d:8e:7f    ipv4         10.0.0.5/24\n"))
                .isEqualTo("192.168.122.174");
    }

    @Test
    void ignoresAnIpv6OnlyInterface() {
        assertThat(LocalVms.address("""
             vnet3      52:54:00:1a:2b:3c    ipv6         fe80::1/64
            """)).isNull();
    }

    @Test
    void startsAMachineThatIsNotRunningAndLeavesOneThatIs() throws IOException {
        final List<String> asked = new ArrayList<>();
        final LocalVms vms = new LocalVms(Map.of("ubuntu", "ubuntu26.04"), arguments -> {
            asked.add(String.join(" ", arguments));
            return switch (arguments[0]) {
                case "domstate" -> asked.size() > 3 ? "running\n" : "shut off\n";
                case "domifaddr" -> DOMIFADDR;
                default -> "";
            };
        });
        try (Lease lease = vms.acquire(spec())) {
            assertThat(lease.address()).isEqualTo("192.168.122.174");
            assertThat(lease.name()).isEqualTo("ubuntu26.04");
        }
        assertThat(asked).containsExactly("domstate ubuntu26.04", "start ubuntu26.04",
                "domifaddr ubuntu26.04");
    }

    @Test
    void doesNotStartAMachineThatIsAlreadyUp() throws IOException {
        // The stub answers in English and so this passed while the real thing failed: virsh
        // translates, and on a German host 'domstate' says 'laufend'. The check never matched,
        // a running machine was started again, and it read like a broken hypervisor. The fix is
        // in LocalVms - a forced C locale - and it is not something this test can see.
        final List<String> asked = new ArrayList<>();
        final LocalVms vms = new LocalVms(Map.of("ubuntu", "ubuntu26.04"), arguments -> {
            asked.add(arguments[0]);
            return "domstate".equals(arguments[0]) ? "running\n" : DOMIFADDR;
        });
        try (Lease lease = vms.acquire(spec())) {
            assertThat(lease.address()).isNotBlank();
        }
        assertThat(asked).doesNotContain("start");
    }

    @Test
    void saysWhichMachinesThisHostHasWhenAskedForOneItDoesNot() {
        final LocalVms vms = new LocalVms(Map.of("ubuntu", "ubuntu26.04", "fedora", "fedora"),
                arguments -> "");
        assertThatThrownBy(() -> vms.acquire(
                new Spec("x", "debian", Spec.DEFAULT_TYPES, "claude", Keys.generated(), false)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("No local machine for 'debian'")
                .hasMessageContaining("ubuntu");
    }

    @Test
    void releasingLeavesTheMachineRunning() throws IOException {
        // The opposite of a rented server and for the opposite reason: nothing is billing, and
        // booting again costs forty seconds that buy nothing.
        final List<String> asked = new ArrayList<>();
        final LocalVms vms = new LocalVms(Map.of("ubuntu", "ubuntu26.04"), arguments -> {
            asked.add(arguments[0]);
            return "domstate".equals(arguments[0]) ? "running\n" : DOMIFADDR;
        });
        vms.acquire(spec()).close();
        assertThat(asked).doesNotContain("shutdown", "destroy");
    }

    private static Spec spec() {
        return Spec.of("local", "ubuntu", "claude", Keys.generated());
    }
}
