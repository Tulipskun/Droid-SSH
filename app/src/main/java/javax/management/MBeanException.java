package javax.management;

/**
 * Minimal stub: เหตุผลเดียวกับ ReflectionException
 * (ExceptionUtils.peelException อ้างถึงบน Android ที่ไม่มี JMX)
 */
public class MBeanException extends Exception {
    private final Exception exception;

    public MBeanException(Exception e) {
        super(e);
        this.exception = e;
    }

    public MBeanException(Exception e, String message) {
        super(message, e);
        this.exception = e;
    }

    public Exception getTargetException() {
        return exception;
    }
}
