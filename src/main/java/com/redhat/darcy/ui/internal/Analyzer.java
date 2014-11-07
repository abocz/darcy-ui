/*
 Copyright 2014 Red Hat, Inc. and/or its affiliates.

 This file is part of darcy-ui.

 This program is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.redhat.darcy.ui.internal;

import static com.redhat.darcy.ui.matchers.DarcyMatchers.displayed;
import static com.redhat.darcy.ui.matchers.DarcyMatchers.loaded;
import static com.redhat.darcy.ui.matchers.DarcyMatchers.present;

import com.redhat.darcy.ui.DarcyException;
import com.redhat.darcy.ui.NoRequiredElementsException;
import com.redhat.darcy.ui.annotations.Context;
import com.redhat.darcy.ui.annotations.NotRequired;
import com.redhat.darcy.ui.annotations.Require;
import com.redhat.darcy.ui.annotations.RequireAll;
import com.redhat.darcy.ui.api.View;
import com.redhat.darcy.ui.api.elements.Element;
import com.redhat.darcy.ui.api.elements.Findable;
import com.redhat.synq.Condition;
import com.redhat.synq.HamcrestCondition;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class Analyzer {
    private final Object view;
    private final List<Field> required;

    private List<Field> requiredLists;
    private List<Object> requiredObjects;

    private List<Condition<?>> isLoaded;
    private List<Condition<?>> isDisplayed;
    private List<Condition<?>> isPresent;

    /**
     * @param view A view with at least one field that is an
     * {@link com.redhat.darcy.ui.api.elements.Element}, {@link com.redhat.darcy.ui.api.View},
     * {@link com.redhat.darcy.ui.api.elements.Findable}, or {@link java.util.List} of those types,
     * and is annotated as required.
     * @param fields All of the fields declared for the specified View (including fields in parent
     * classes}. Fields are expected to be accessible.
     */
    public Analyzer(Object view, List<Field> fields) {
        this.view = Objects.requireNonNull(view, "view");
        this.required = filterRequired(Objects.requireNonNull(fields, "fields"));
    }

    public List<Condition<?>> getLoadConditions() {
        if (isLoaded == null) {
            isLoaded = new ArrayList<>();

            analyze();

            isLoaded.addAll(requiredObjects.stream()
                    .map(this::objectToLoadCondition)
                    .filter(c -> c != null)
                    .collect(Collectors.toList()));

            // TODO: Lists

            if(isLoaded.isEmpty()) {
                throw new NoRequiredElementsException(this);
            }
        }

        return isLoaded;
    }

    public List<Condition<?>> getDisplayConditions() {
        if (isDisplayed == null) {
            isDisplayed = new ArrayList<>();

            analyze();

            isDisplayed.addAll(requiredObjects.stream()
                    .filter(o -> o instanceof Element) // Should check instance or field type?
                    .map(e -> HamcrestCondition.match((Element) e, displayed()))
                    .collect(Collectors.toList()));

            // TODO: Lists

            if(isDisplayed.isEmpty()) {
                throw new NoRequiredElementsException(this);
            }
        }

        return isDisplayed;
    }

    public List<Condition<?>> getIsPresentConditions() {
        if (isPresent == null) {
            isPresent = new ArrayList<>();

            analyze();

            isPresent.addAll(requiredObjects.stream()
                    .filter(o -> o instanceof Findable) // Should check instance or field type?
                    .map(f -> HamcrestCondition.match((Findable) f, present()))
                    .collect(Collectors.toList()));

            // TODO: Lists

            if(isPresent.isEmpty()) {
                throw new NoRequiredElementsException(this);
            }
        }

        return isPresent;
    }

    @SuppressWarnings("unchecked")
    private boolean isListLoaded(Field field) {

        Annotation annotation = field.getAnnotation(Require.class);

        if (annotation != null) {
            List<Object> elementList = (List<Object>)fieldToObject(field);
            int exactly = ((Require) annotation).exactly();
            int atLeast= ((Require) annotation).atLeast();
            int atMost = ((Require) annotation).atMost();

            int count = 0;

            for(Object element : elementList) {
                if (objectToLoadCondition(element) != null) {
                    count++;
                }
            }

            boolean atLeastMet = count >= atLeast;
            boolean atMostMet = (atMost == Integer.MAX_VALUE) || (count <= atMost);
            boolean exactlyMet = (exactly == Integer.MAX_VALUE) || (count == exactly);

            return exactlyMet || (atLeastMet && atMostMet);
        }
        return true;
    }

    /**
     * Reflectively examine the view, gathering, filtering, and sorting fields. The results are
     * assigned to {@link #requiredLists} and {@link #requiredObjects}; fields that are collections and
     * objects of fields that are not collections, respectively. This method is idempotent;
     * subsequent calls after the first have no effect (fields need only be analyzed once).
     *
     * <p>Fields cannot be analyzed before they are assigned, which is why this analyze is delayed
     * until needed. This way you can instantiate an Analyzer in a constructor or {@code <init>}
     * without worrying about whether your class or subclass fields are assigned yet.
     */
    private void analyze() {
        if (requiredLists != null && requiredObjects != null) {
            return;
        }

        requiredLists = required.stream()
                .filter(this::isList)
                .collect(Collectors.toList());

        requiredObjects = required.stream()
                .filter(f -> !isList(f))
                .map(this::fieldToObject)
                .collect(Collectors.toList());

        if (/*requiredLists.isEmpty() &&*/ requiredObjects.isEmpty()) {
            throw new NoRequiredElementsException(view);
        }
    }

    private List<Field> filterRequired(List<Field> fields) {
        return fields.stream()
                .filter(this::isViewElementFindableOrList)
                .filter(this::isNotAnnotatedWithContext)
                .filter(this::isRequired)
                .collect(Collectors.toList());
    }

    private boolean isList(Field f) {
        return List.class.isAssignableFrom(f.getType());
    }

    private Object fieldToObject(Field f) {
        try {
            return f.get(view);
        } catch (IllegalAccessException e) {
            throw new DarcyException("Couldn't analyze required fields.", e);
        }
    }

    /**
     * Takes an object, and determines a condition for that object that should satisfy the
     * containing view is loaded. Different conditions are made depending on the type of object.
     *
     * <table>
     *     <thead>
     *         <tr>
     *             <td>Type</td>
     *             <td>Method</td>
     *         </tr>
     *     </thead>
     *     <tbody>
     *         <tr>
     *             <td>{@link com.redhat.darcy.ui.api.View}</td>
     *             <td>{@link com.redhat.darcy.ui.api.View#isLoaded()}</td>
     *         </tr>
     *         <tr>
     *             <td>{@link com.redhat.darcy.ui.api.elements.Element}</td>
     *             <td>{@link com.redhat.darcy.ui.api.elements.Element#isDisplayed()}</td>
     *         </tr>
     *         <tr>
     *             <td>{@link com.redhat.darcy.ui.api.elements.Findable}</td>
     *             <td>{@link com.redhat.darcy.ui.api.elements.Findable#isPresent()}</td>
     *         </tr>
     *     </tbody>
     * </table>
     */
    private Condition<?> objectToLoadCondition(Object fieldObject) {
        if (fieldObject instanceof View) {
            return HamcrestCondition.match((View) fieldObject, loaded());
        } else if (fieldObject instanceof Element) {
            return HamcrestCondition.match((Element) fieldObject, displayed());
        } else if (fieldObject instanceof Findable) {
            return HamcrestCondition.match((Findable) fieldObject, present());
        }

        return null;
    }

    /**
     * Those are only supported types which make sense to look at.
     */
    private boolean isViewElementFindableOrList(Field field) {
        Class<?> fieldType = field.getType();
        return View.class.isAssignableFrom(fieldType)
                || Element.class.isAssignableFrom(fieldType)
                || Findable.class.isAssignableFrom(fieldType)
                || List.class.isAssignableFrom(fieldType);
    }

    /**
     * Contexts must be implicitly present if anything in this view is is to be present.
     */
    private boolean isNotAnnotatedWithContext(Field field) {
        return field.getAnnotation(Context.class) == null;
    }

    /**
     * Determines whether a field is required or not based on combination of Require, RequireAll,
     * and NotRequired annotations.
     */
    private boolean isRequired(Field field) {
        /*
        Annotation annotation = field.getAnnotation(Require.class);
        int exactly = ((Require) annotation).exactly();
        int atLeast= ((Require) annotation).atLeast();
        int atMost = ((Require) annotation).atMost();
        */
        return field.getAnnotation(Require.class) != null
                // Use the field's declaring class for RequireAll; may be a super class
                || (field.getDeclaringClass().getAnnotation(RequireAll.class) != null
                && field.getAnnotation(NotRequired.class) == null);
    }
}
