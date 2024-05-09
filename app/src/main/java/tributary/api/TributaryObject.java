package tributary.api;

public abstract class TributaryObject {
    private String id;

    public TributaryObject(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }
}
