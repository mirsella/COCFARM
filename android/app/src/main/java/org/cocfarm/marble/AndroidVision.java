package org.cocfarm.marble;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

final class AndroidVision implements AutoCloseable {
    final BuilderVision vision;
    private TextRecognizer recognizer;

    AndroidVision(AssetManager assets) throws IOException {
        if (!OpenCVLoader.initLocal()) throw new IOException("Couldn't load the bundled vision library.");
        vision = new BuilderVision(name -> load(assets, name), this::read);
    }

    BuilderVision.Frame frame(byte[] capture, BuilderRun.Options options, BuilderVision.Input input) throws IOException {
        return vision.frame(capture, options, input);
    }

    private static Mat load(AssetManager assets, String name) throws IOException {
        try (var stream = assets.open("builder/" + name + ".png")) {
            Bitmap image = BitmapFactory.decodeStream(stream);
            if (image == null) throw new IOException("Couldn't read Builder template " + name);
            Mat pixels = new Mat();
            try {
                Utils.bitmapToMat(image, pixels);
                Imgproc.cvtColor(pixels, pixels, Imgproc.COLOR_RGBA2RGB);
                return pixels;
            } catch (RuntimeException e) {
                pixels.release();
                throw e;
            } finally {
                image.recycle();
            }
        }
    }

    private String read(Mat number) {
        if (recognizer == null) recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        Bitmap image = Bitmap.createBitmap(number.cols(), number.rows(), Bitmap.Config.ARGB_8888);
        try {
            Utils.matToBitmap(number, image);
            var task = recognizer.process(InputImage.fromBitmap(image, 0));
            task.addOnCompleteListener(Runnable::run, done -> image.recycle());
            return Tasks.await(task, 2, TimeUnit.SECONDS).getText();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        } catch (ExecutionException | TimeoutException e) {
            Log.w("MarbleRunner", "Couldn't read a battle result field", e);
            return "";
        } catch (RuntimeException e) {
            image.recycle();
            throw e;
        }
    }

    @Override public void close() {
        vision.close();
        if (recognizer != null) recognizer.close();
    }
}
