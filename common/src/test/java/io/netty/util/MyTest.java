package io.netty.util;

import java.nio.ByteBuffer;

public class MyTest {

    public static void main(String[] args) {

        ByteBuffer buffer = ByteBuffer.allocate(100);
        byte temp = 120;
        byte temp1 = 121;
        byte temp2 = 122;
        buffer.put(temp);
        buffer.put(temp1);
        buffer.put(temp2);

        buffer.flip();

        System.out.println(buffer.get());
        System.out.println(buffer.get());
        System.out.println(buffer.get());

    }
}
