/**
 * Copyright (c) 2025 The Socketio4j Project
 * Parent project : Copyright (c) 2012-2025 Nikita Koksharov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.socketio4j.socketio.annotation;

/**
 * @author https://github.com/sanjomo
 * @date 02/08/26 6:40 pm
 */

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.CONSTRUCTOR;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PACKAGE;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.CLASS;

/**
 * Marks an API as internal to socketio4j.
 *
 * <p>Types and members annotated with {@code @Internal} are implementation
 * details and are <strong>NOT</strong> part of the supported public API.
 * They may change, move, or be removed without notice in any release.
 *
 * <p>Application code should not depend on these APIs.
 */
@Documented
@Retention(CLASS)
@Target({
        TYPE,
        METHOD,
        CONSTRUCTOR,
        FIELD,
        PACKAGE
})
public @interface Internal {
}