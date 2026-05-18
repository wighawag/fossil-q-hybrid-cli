package qhybrid.linux;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "fossil-q", description = "Fossil Q Hybrid CLI (coin-cell models)",
         mixinStandardHelpOptions = true, version = "0.1.0")
public class Main implements Runnable {

    @Option(names = {"-d", "--device"}, description = "Watch MAC address (e.g. AA:BB:CC:DD:EE:FF)")
    String macAddress;

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }
}
