package com.xtremelabs.droidsugar;

import java.util.ArrayList;
import java.util.List;

import javassist.CannotCompileException;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtConstructor;
import javassist.CtField;
import javassist.CtMethod;
import javassist.CtNewConstructor;
import javassist.CtNewMethod;
import javassist.Modifier;
import javassist.NotFoundException;
import javassist.Translator;

public class AndroidBodyStrippingTranslator implements Translator {
    private static final List<AndroidBodyStrippingTranslator> INSTANCES = new ArrayList<AndroidBodyStrippingTranslator>();

    private int index;
    private ClassHandler classHandler;

    public AndroidBodyStrippingTranslator(ClassHandler classHandler) {
        this.classHandler = classHandler;
        index = addInstance(this);
    }

    synchronized static private int addInstance(AndroidBodyStrippingTranslator androidTranslator) {
        INSTANCES.add(androidTranslator);
        return INSTANCES.size() - 1;
    }

    synchronized static public AndroidBodyStrippingTranslator get(int index) {
        return INSTANCES.get(index);
    }


    public void start(ClassPool classPool) throws NotFoundException, CannotCompileException {
    }

    public void onLoad(ClassPool classPool, String className) throws NotFoundException, CannotCompileException {
        boolean needsStripping = className.startsWith("android.")
                || className.startsWith("org.apache.http");

        CtClass ctClass = classPool.get(className);
        if (needsStripping) {
            int modifiers = ctClass.getModifiers();
            if (Modifier.isFinal(modifiers)) {
                ctClass.setModifiers(modifiers & ~Modifier.FINAL);
            }

            if (ctClass.isInterface()) return;

            classHandler.instrument(ctClass);
            fixConstructors(ctClass);
            fixMethods(ctClass);
        }
    }

    private void fixConstructors(CtClass ctClass) throws CannotCompileException, NotFoundException {
        boolean needsDefault = true;
        for (CtConstructor ctConstructor : ctClass.getConstructors()) {
            String methodBody = generateMethodBody(ctClass,
                    new CtMethod(CtClass.voidType, "<init>", ctConstructor.getParameterTypes(), ctClass),
                    CtClass.voidType,
                    Type.VOID,
                    true,
                    false);

            ctConstructor.setBody("{\n" + methodBody + "\n}");
            if (ctConstructor.getParameterTypes().length == 0) {
                needsDefault = false;
            }
        }

        if (needsDefault) {
            ctClass.addConstructor(CtNewConstructor.skeleton(new CtClass[0], new CtClass[0], ctClass));
        }
    }

    private void fixMethods(CtClass ctClass) throws NotFoundException, CannotCompileException {
        for (CtMethod ctMethod : ctClass.getDeclaredMethods()) {
            int modifiers = ctMethod.getModifiers();
            boolean wasNative = false;
            if (Modifier.isNative(modifiers)) {
                wasNative = true;
                modifiers = modifiers & ~Modifier.NATIVE;
            }
            if (Modifier.isFinal(modifiers)) {
                modifiers = modifiers & ~Modifier.FINAL;
            }
            ctMethod.setModifiers(modifiers);

            CtClass returnCtClass = ctMethod.getReturnType();
            Type returnType = Type.find(returnCtClass);

            String methodName = ctMethod.getName();
            CtClass[] paramTypes = ctMethod.getParameterTypes();

            boolean isAbstract = (ctMethod.getModifiers() & Modifier.ABSTRACT) != 0;
//            if (!isAbstract) {
//                if (methodName.startsWith("set") && paramTypes.length == 1) {
//                    String fieldName = "__" + methodName.substring(3);
//                    if (declareField(ctClass, fieldName, paramTypes[0])) {
//                        methodBody = fieldName + " = $1;\n" + methodBody;
//                    }
//                } else if (methodName.startsWith("get") && paramTypes.length == 0) {
//                    String fieldName = "__" + methodName.substring(3);
//                    if (declareField(ctClass, fieldName, returnType)) {
//                        methodBody = "return " + fieldName + ";\n";
//                    }
//                }
//            }

            boolean returnsVoid = returnType.isVoid();
            boolean isStatic = Modifier.isStatic(modifiers);

            String methodBody;
            if (!wasNative && !isAbstract) {
                methodBody = generateMethodBody(ctClass, ctMethod, returnCtClass, returnType, returnsVoid, isStatic);
            } else {
                methodBody = returnsVoid ? "" : "return " + returnType.defaultReturnString() + ";";
            }

            CtMethod newMethod = CtNewMethod.make(
                ctMethod.getModifiers(),
                returnCtClass,
                methodName,
                paramTypes,
                ctMethod.getExceptionTypes(),
                "{\n" + methodBody + "\n}",
                ctClass);
            ctMethod.setBody(newMethod, null);
        }
    }

    private String generateMethodBody(CtClass ctClass, CtMethod ctMethod, CtClass returnCtClass, Type returnType, boolean returnsVoid, boolean aStatic) throws NotFoundException {
        String methodBody;
        StringBuilder buf = new StringBuilder();
        if (!returnsVoid) {
            buf.append("Object x = ");
        }
        buf.append(AndroidBodyStrippingTranslator.class.getName());
        buf.append(".get(");
        buf.append(index);
        buf.append(").methodInvoked(");
        buf.append(ctClass.getName());
        buf.append(".class, \"");
        buf.append(ctMethod.getName());
        buf.append("\", ");
        if (!aStatic) {
            buf.append("this");
        } else {
            buf.append("null");
        }
        buf.append(", ");

        appendParamTypeArray(buf, ctMethod);
        buf.append(", ");
        appendParamArray(buf, ctMethod);

        buf.append(")");
        buf.append(";\n");

        if (!returnsVoid) {
            buf.append("if (x != null) return ((");
            buf.append(returnType.nonPrimitiveClassName(returnCtClass));
            buf.append(") x)");
            buf.append(returnType.unboxString());
            buf.append(";\n");
            buf.append("return ");
            buf.append(returnType.defaultReturnString());
            buf.append(";");
        }

        methodBody = buf.toString();
        return methodBody;
    }

    private void appendParamTypeArray(StringBuilder buf, CtMethod ctMethod) throws NotFoundException {
        CtClass[] parameterTypes = ctMethod.getParameterTypes();
        if (parameterTypes.length == 0) {
            buf.append("new String[0]");
        } else {
            buf.append("new String[] {");
            for (int i = 0; i < parameterTypes.length; i++) {
                if (i > 0) buf.append(", ");
                buf.append("\"");
                CtClass parameterType = parameterTypes[i];
                buf.append(parameterType.getName());
                buf.append("\"");
            }
            buf.append("}");
        }
    }

    private void appendParamArray(StringBuilder buf, CtMethod ctMethod) throws NotFoundException {
        int parameterCount = ctMethod.getParameterTypes().length;
        if (parameterCount == 0) {
            buf.append("new Object[0]");
        } else {
            buf.append("new Object[] {");
            for (int i = 0; i < parameterCount; i++) {
                if (i > 0) buf.append(", ");
                buf.append(AndroidBodyStrippingTranslator.class.getName());
                buf.append(".autobox(");
                buf.append("$").append(i + 1);
                buf.append(")");
            }
            buf.append("}");
        }
    }

    @SuppressWarnings({"UnusedDeclaration"})
    public Object methodInvoked(Class clazz, String methodName, Object instance, String[] paramTypes, Object[] params) {
        return classHandler.methodInvoked(clazz, methodName, instance, paramTypes, params);
    }

    @SuppressWarnings({"UnusedDeclaration"})
    public static Object autobox(Object o) {
        return o;
    }

    @SuppressWarnings({"UnusedDeclaration"})
    public static Object autobox(boolean o) {
        return o;
    }

    @SuppressWarnings({"UnusedDeclaration"})
    public static Object autobox(byte o) {
        return o;
    }

    @SuppressWarnings({"UnusedDeclaration"})
    public static Object autobox(char o) {
        return o;
    }

    @SuppressWarnings({"UnusedDeclaration"})
    public static Object autobox(short o) {
        return o;
    }

    @SuppressWarnings({"UnusedDeclaration"})
    public static Object autobox(int o) {
        return o;
    }

    @SuppressWarnings({"UnusedDeclaration"})
    public static Object autobox(long o) {
        return o;
    }

    @SuppressWarnings({"UnusedDeclaration"})
    public static Object autobox(float o) {
        return o;
    }

    @SuppressWarnings({"UnusedDeclaration"})
    public static Object autobox(double o) {
        return o;
    }

    private boolean declareField(CtClass ctClass, String fieldName, CtClass fieldType) throws CannotCompileException, NotFoundException {
        CtMethod ctMethod = getMethod(ctClass, "get" + fieldName, "");
        if (ctMethod == null) {
            return false;
        }
        CtClass getterFieldType = ctMethod.getReturnType();

        if (!getterFieldType.equals(fieldType)) {
            return false;
        }

        if (getField(ctClass, fieldName) == null) {
            CtField field = new CtField(fieldType, fieldName, ctClass);
            field.setModifiers(Modifier.PRIVATE);
            ctClass.addField(field);
        }

        return true;
    }

    private CtField getField(CtClass ctClass, String fieldName) {
        try {
            return ctClass.getField(fieldName);
        } catch (NotFoundException e) {
            return null;
        }
    }

    private CtMethod getMethod(CtClass ctClass, String methodName, String desc) {
        try {
            return ctClass.getMethod(methodName, desc);
        } catch (NotFoundException e) {
            return null;
        }
    }
}
