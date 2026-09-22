package javax.management;

/**
 * Minimal stub: Android ไม่มี JMX แต่ MINA sshd อ้างถึงคลาสนี้ใน
 * ExceptionUtils.peelException (instanceof) ทำให้ class resolution ล้ม
 * -> NoClassDefFoundError ตั้งแต่ ServerBuilder.<clinit>
 * stub นี้ทำให้ linkage ผ่าน (บน Android จะไม่มีใคร throw จริง)
 */
public class ReflectionException extends Exception {
    private final Exception exception;

    public ReflectionException(Exception e) {
        super(e);
        this.exception = e;
    }

    public ReflectionException(Exception e, String message) {
        super(message, e);
        this.exception = e;
    }

    public Exception getTargetException() {
        return exception;
    }
}
