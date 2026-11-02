package com.classos.files;

import com.classos.Fixtures;
import com.classos.IntegrationTestBase;
import com.classos.common.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class FileStorageTest extends IntegrationTestBase {

    @Autowired FileStorageService storage;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager tx;

    @Test
    void rolledBackUploadLeavesNoFileBehind() {
        long user = new Fixtures(jdbc).user("TEACHER");
        AtomicReference<StoredFile> stored = new AtomicReference<>();
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(tx).executeWithoutResult(s -> {
            stored.set(storage.store(new MockMultipartFile("file", "notes.pdf", "application/pdf", new byte[]{1, 2, 3}), user));
            assertTrue(storage.existsOnDisk(stored.get().id()));
            throw new IllegalStateException("something later in the transaction failed");
        }));
        assertFalse(storage.existsOnDisk(stored.get().id()), "the file is removed when the transaction rolls back");
        Integer rows = jdbc.queryForObject("SELECT count(*) FROM stored_files WHERE id = ?", Integer.class, stored.get().id());
        assertEquals(0, rows);
    }

    @Test
    void namesAreCleanedAndDangerousTypesRefused() {
        assertEquals("passwd.txt", FileStorageService.cleanName("../../etc/passwd.txt"));
        assertEquals("report.pdf", FileStorageService.cleanName("C:\\Users\\me\\report.pdf"));
        assertEquals("file", FileStorageService.cleanName("   "));

        long user = new Fixtures(jdbc).user("TEACHER");
        for (String bad : new String[]{"page.html", "image.svg", "run.exe", "noextension"}) {
            ApiException e = assertThrows(ApiException.class, () -> new TransactionTemplate(tx).executeWithoutResult(s ->
                    storage.store(new MockMultipartFile("file", bad, "text/html", new byte[]{1}), user)));
            assertEquals(HttpStatus.BAD_REQUEST, e.getStatus(), bad);
        }
    }

    @Test
    void storingOutsideATransactionIsAProgrammingError() {
        assertThrows(IllegalStateException.class,
                () -> storage.store(new MockMultipartFile("file", "a.pdf", "application/pdf", new byte[]{1}), 1));
    }
}
