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

package org.saidone.quizmaker.controller;

import lombok.RequiredArgsConstructor;
import lombok.val;
import org.saidone.quizmaker.service.ShortLinkService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

/** Resolves internal short links; the destination still enforces its own access rules. */
@Controller
@RequiredArgsConstructor
public class ShortLinkWebController {
    private final ShortLinkService shortLinkService;

    @GetMapping("/q/{code}")
    public ResponseEntity<Void> redirect(@PathVariable String code) {
        val path = shortLinkService.resolve(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Link non disponibile"));
        return ResponseEntity.status(HttpStatus.FOUND).cacheControl(CacheControl.noStore())
                .location(URI.create(ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString() + path)).build();
    }
}
