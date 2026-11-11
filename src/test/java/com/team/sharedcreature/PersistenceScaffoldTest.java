package com.team.sharedcreature;

import com.team.sharedcreature.group.domain.Group;
import jakarta.persistence.Table;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PersistenceScaffoldTest {

    @Test
    void 설계한_41개_업무테이블에_Entity와_Repository가_각각_존재한다() throws IOException {
        Path source = Path.of("src/main/java");
        long entities;
        long repositories;
        try (var files = Files.walk(source)) {
            entities = files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> read(path).contains("@Entity"))
                    .count();
        }
        try (var files = Files.walk(source)) {
            repositories = files.filter(path -> path.toString().endsWith("Repository.java"))
                    .filter(path -> read(path).contains("extends JpaRepository"))
                    .count();
        }

        assertThat(entities).isEqualTo(41);
        assertThat(repositories).isEqualTo(41);
    }

    @Test
    void 그룹_Entity는_MySQL_예약어가_아닌_game_groups_테이블을_사용한다() {
        Table table = Group.class.getAnnotation(Table.class);

        assertThat(table).isNotNull();
        assertThat(table.name()).isEqualTo("game_groups");
    }

    private String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
