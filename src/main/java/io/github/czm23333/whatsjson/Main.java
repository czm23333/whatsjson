package io.github.czm23333.whatsjson;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Scanner;

public class Main {

    public static void main(String[] args) throws IOException {
        WhatsJson whatsJson = new WhatsJson();

        Scanner scanner = new Scanner(System.in);
        byte[] bytes = Files.readAllBytes(Paths.get(scanner.next()));
        for (int i = 1; i <= 100; ++i) whatsJson.fromJson(bytes);

        whatsJson.shutdown();
    }
}