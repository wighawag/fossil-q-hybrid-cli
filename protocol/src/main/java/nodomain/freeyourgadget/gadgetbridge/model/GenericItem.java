package nodomain.freeyourgadget.gadgetbridge.model;

public class GenericItem {
    private final String name;
    private final String details;

    public GenericItem(String name, String details) {
        this.name = name;
        this.details = details;
    }

    public String getName() {
        return name;
    }

    public String getDetails() {
        return details;
    }
}
