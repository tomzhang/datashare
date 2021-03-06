package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.icij.datashare.user.User;
import org.icij.datashare.extract.RedisUserDocumentQueue;
import org.icij.datashare.user.UserTask;
import org.icij.extract.Scanner;
import org.icij.extract.ScannerVisitor;
import org.icij.extract.document.DocumentFactory;
import org.icij.task.DefaultTask;
import org.icij.task.Options;
import org.icij.task.annotation.OptionsClass;

import java.nio.file.Path;

@OptionsClass(Scanner.class)
@OptionsClass(DocumentFactory.class)
public class ScanTask extends DefaultTask<Path> implements UserTask {
    private final Scanner scanner;
    private final RedisUserDocumentQueue queue;
    private final Path path;
    private final User user;

    @Inject
    public ScanTask(@Assisted User user, @Assisted Path path, @Assisted final Options<String> userOptions) {
        this.path = path.resolve(user.getPath());
        this.user = user;
        Options<String> allOptions = options().createFrom(userOptions);
        queue = new RedisUserDocumentQueue(user, userOptions);
        scanner = new Scanner(new DocumentFactory(allOptions), queue).configure(allOptions);
    }

    @Override
    public Path call() throws Exception {
        ScannerVisitor scannerVisitor = scanner.createScannerVisitor(path);
        Path path = scannerVisitor.call();
        queue.close();
        return path;
    }

    @Override
    public User getUser() {
        return user;
    }
}
