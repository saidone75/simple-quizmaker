/*
 * Alice's Simple Quiz Maker - fun quizzes for curious minds
 * Copyright (C) 2026 Miss Alice & Saidone
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.saidone.quizmaker.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

/** Generic mapping: independent of quizzes and of the application hostname. */
@Entity
@Table(name = "short_links")
@Getter
@NoArgsConstructor
public class ShortLink implements Persistable<String> {
    @Transient
    private boolean newEntity = true;
    @Id
    @Column(length = 64, nullable = false, updatable = false)
    private String code;

    @Column(name = "target_path", length = 512, nullable = false, unique = true, updatable = false)
    private String targetPath;

    @Override
    public String getId() {
        return code;
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }

    @PostLoad
    @PostPersist
    private void markPersisted() {
        newEntity = false;
    }

    public ShortLink(String code, String targetPath) {
        this.code = code;
        this.targetPath = targetPath;
    }
}
