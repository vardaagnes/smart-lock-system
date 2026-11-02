package com.smarthome.permission.demo;

import com.smarthome.permission.entity.TempTicket;
import com.smarthome.permission.service.TicketService;

/**
 * 手工验证入口：跑一次看输出对不对（对齐方案设计文档 6.2）。
 * 集成到 Controller 后这个类可以删掉。
 *
 * 运行方式：IDEA 里右键 → Run 'TicketDemo.main()'
 */
public class TicketDemo {

    public static void main(String[] args) {
        TicketService service = new TicketService();

        String pin = "864275";
        long now = System.currentTimeMillis() / 1000;

        TempTicket t = service.generateTicket(
                "lock-001", "open",
                now, now + 2 * 60 * 60,   // 2 小时有效
                1,                        // 只开 1 次
                pin
        );

        System.out.println("t_id       = " + t.getTId());
        System.out.println("lock_id    = " + t.getLockId());
        System.out.println("perm       = " + t.getPerm());
        System.out.println("from       = " + t.getFrom());
        System.out.println("to         = " + t.getTo());
        System.out.println("max_uses   = " + t.getMaxUses());
        System.out.println("salt       = " + t.getSalt());
        System.out.println("pin_hash   = " + t.getPinHash());
        System.out.println("iat        = " + t.getIat());
        System.out.println("sig_c      = " + t.getSigC());
        System.out.println();
        System.out.println("待签名内容  = " + service.canonicalString(t));
        System.out.println();
        System.out.println("PIN 正确时复算一致 = " + service.matchesPin(t, pin));
        System.out.println("PIN 错误时复算一致 = " + service.matchesPin(t, "000000"));
    }
}
