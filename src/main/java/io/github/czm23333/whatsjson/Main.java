package io.github.czm23333.whatsjson;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Scanner;

public class Main {

    public static void main(String[] args) throws IOException {
        WhatsJson whatsJson = new WhatsJson();

        Scanner scanner = new Scanner(System.in);
        System.out.println(whatsJson.toJson(whatsJson.fromJson(String.join(System.lineSeparator(), Files.readAllLines(Paths.get(scanner.next()))))));

        whatsJson.shutdown();
    }
}