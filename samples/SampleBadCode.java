import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 示例代码 - 包含各种问题
 * 用于测试代码检查器
 */
public class SampleBadCode {
    // 不规范的命名
    private int BAD_VAR = 123;
    private String user_name = "test";
    public static final int max_size = 1024; // 常量命名不规范

    // 魔法数字
    public double calculate(double value) {
        return value * 3.14159 * 256 / 1024;
    }

    // 空指针风险：参数未判空
    public String getUserName(User user) {
        return user.getName();
    }

    // 空 catch 块
    public void safeParse(String input) {
        try {
            int x = Integer.parseInt(input);
            System.out.println(x);
        // 空 catch，吞掉异常（注释在体外：catch 体本身空且无说明，供 EMPTY_CATCH 规则演示）
        } catch (Exception e) {
        }
    }

    // 资源泄露：未关闭 InputStream
    public String readFile(String path) throws IOException {
        InputStream is = new FileInputStream(path);
        byte[] data = new byte[1024];
        is.read(data);
        return new String(data);
    }

    // 高复杂度方法
    public int complexLogic(int a, int b, int c, int d, int e) {
        int result = 0;
        if (a > 0) {
            if (b > 0) {
                if (c > 0) {
                    if (d > 0) {
                        result = a + b + c + d;
                    } else {
                        result = a + b + c;
                    }
                } else if (c == 0) {
                    result = a + b;
                } else {
                    result = a - b;
                }
            } else if (b == 0) {
                result = a;
            } else {
                result = a * 2;
            }
        } else if (a == 0) {
            result = b + c;
        } else {
            result = -a;
        }
        return result;
    }

    // 废弃方法
    @Deprecated
    public void oldMethod() {
        System.out.println("This method is deprecated");
    }
}

class User {
    private String name;
    public String getName() { return name; }
}
