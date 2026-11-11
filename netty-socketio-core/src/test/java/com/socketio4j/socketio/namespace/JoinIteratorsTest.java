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
package com.socketio4j.socketio.namespace;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.socketio4j.socketio.misc.CompositeIterable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JoinIteratorsTest {

    @Test
    public void testIterator() {
        List<Integer> list1 = Arrays.asList(1, 2);
        List<Integer> list2 = Arrays.asList(3, 4);
        CompositeIterable<Integer> iterators = new CompositeIterable<Integer>(list1, list2);

        // for nomemory test
        for (Integer integer : iterators) {
        }

        List<Integer> mainList = new ArrayList<Integer>();
        for (Integer integer : iterators) {
            mainList.add(integer);
        }
        assertEquals(Arrays.asList(1, 2, 3, 4), mainList);
        mainList.removeAll(list1);
        mainList.removeAll(list2);
        assertTrue(mainList.isEmpty());

    }

    @Test
    void emptyInputsAndExhaustionFollowIteratorContract() {
        CompositeIterable<Integer> values = new CompositeIterable<>(java.util.Collections.emptyList(),
                Arrays.asList(1), java.util.Collections.emptyList(), Arrays.asList(2), java.util.Collections.emptyList());
        java.util.Iterator<Integer> iterator = values.iterator();
        assertTrue(iterator.hasNext());
        assertTrue(iterator.hasNext());
        assertEquals(Integer.valueOf(1), iterator.next());
        assertEquals(Integer.valueOf(2), iterator.next());
        org.junit.jupiter.api.Assertions.assertFalse(iterator.hasNext());
        org.junit.jupiter.api.Assertions.assertThrows(java.util.NoSuchElementException.class, iterator::next);
        java.util.Iterator<Integer> empty = new CompositeIterable<Integer>(java.util.Collections.emptyList()).iterator();
        org.junit.jupiter.api.Assertions.assertFalse(empty.hasNext());
        org.junit.jupiter.api.Assertions.assertThrows(java.util.NoSuchElementException.class, empty::next);
    }

    @Test
    void removeTargetsTheLastReturnedIteratorEvenWhenHasNextMovesToAnotherInput() {
        List<Integer> first = new ArrayList<>(Arrays.asList(1));
        List<Integer> second = new ArrayList<>(Arrays.asList(2));
        java.util.Iterator<Integer> iterator = new CompositeIterable<Integer>(first, second).iterator();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, iterator::remove);
        assertEquals(Integer.valueOf(1), iterator.next());
        assertTrue(iterator.hasNext());
        iterator.remove();
        assertTrue(first.isEmpty());
        assertEquals(Arrays.asList(2), second);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, iterator::remove);
        assertEquals(Integer.valueOf(2), iterator.next());
        org.junit.jupiter.api.Assertions.assertFalse(iterator.hasNext());
        iterator.remove();
        assertTrue(second.isEmpty());
    }

}
