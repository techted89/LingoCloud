import java.lang.reflect.Method;
import android.widget.TextView;
public class test_textview {
    public static void main(String[] args) {
        for (Method m : TextView.class.getDeclaredMethods()) {
            if (m.getName().equals("setText")) {
                System.out.println(m);
            }
        }
    }
}
