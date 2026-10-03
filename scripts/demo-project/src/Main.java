import java.util.ArrayList;
import java.util.List;

/**
 * 视觉验证用的示例工程。
 *
 * ⚠️ 别放在 /tmp 里：机器重启会把 /tmp 清空，而 runIde 打开一个不存在的工程会
 * 一直卡住（不报错、也不退），表现是「出图脚本等 6 分钟然后 exit 143」。
 * 放在仓库里，和出图产物同一个道理。
 */
public class Main {

    private static final List<String> CACHE = new ArrayList<>();

    public static void main(String[] args) {
        OrderService service = new OrderService();
        System.out.println(service.describe(new Order("A-1001", 42)));
    }

    static class Order {
        final String id;
        final int customerId;

        Order(String id, int customerId) {
            this.id = id;
            this.customerId = customerId;
        }
    }

    static class OrderService {
        String describe(Order order) {
            CACHE.add(order.id);
            return "order " + order.id;
        }
    }
}
