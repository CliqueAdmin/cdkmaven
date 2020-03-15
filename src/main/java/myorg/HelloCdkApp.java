package myorg;

import software.amazon.awscdk.core.App;

import java.util.Arrays;

public class HelloCdkApp {
    public static void main(final String[] args) {
        App app = new App();

        try {
            new HelloCdkStack(app, "HelloCdkStack");
        } catch (InterruptedException e) {
            System.out.println("args = " + Arrays.deepToString(args));
        }

        app.synth();
    }
}


