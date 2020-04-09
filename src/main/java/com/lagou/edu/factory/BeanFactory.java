package com.lagou.edu.factory;

import com.lagou.edu.annotation.Autowired;
import com.lagou.edu.annotation.Component;
import com.lagou.edu.annotation.Transactional;
import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.Element;
import org.dom4j.io.SAXReader;
import org.springframework.asm.ClassReader;
import org.springframework.beans.factory.annotation.Lookup;
import org.springframework.context.annotation.ScannedGenericBeanDefinition;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.Assert;
import org.springframework.util.PathMatcher;
import org.springframework.util.StringUtils;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URL;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * @author 应癫
 * <p>
 * 工厂类，生产对象（使用反射技术）
 */
public class BeanFactory {

    private static Map<Class, String> singletonClasses = new HashMap<>();
    private static Map<String, Object> singletonObjects = new HashMap<>();
    private static Map<String, Object> singletonFactories = new HashMap<>();
    private static final String SEPARATOR = "/";
    private static final String PATTERN = "**/*.class";
    private static PathMatcher pathMatcher = new AntPathMatcher();


    public static void main(String[] args) {
        System.out.println(singletonObjects);
    }

    static {
        try {
            // 获取basePackage
            String basePackage = getScanPackage();
            Enumeration<URL> resourceUrls = BeanFactory.class.getClassLoader().getResources(resolveBasePackage(basePackage));
            while (resourceUrls.hasMoreElements()) {
                // 获取basePackage目录下所有的class文件并封装成Resource
                Set<Resource> resources = findPathMatchingResources(resourceUrls);
                // 获取所有需要被IOC容器管理的Class对象
                findMatchSingletonClasses(resources);
                // 创建Bean
                singletonClasses.forEach((aClass, beanName) -> createBean(aClass, beanName));
                // 事务动态代理增强
                handleTransaction();
            }
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    private static void handleTransaction() {
        TransactionProxyFactory transactionProxyFactory = (TransactionProxyFactory) singletonObjects.get("proxyFactory");
        singletonClasses.forEach((c, beanName) -> {
            Transactional transactional = AnnotationUtils.findAnnotation(c, Transactional.class);
            if (Objects.nonNull(transactional)) {
                Object o = singletonObjects.get(beanName);
                if (Objects.nonNull(c.getInterfaces())) {
                    Object jdkProxy = transactionProxyFactory.getJdkProxy(o);
                    singletonObjects.put(beanName, jdkProxy);
                } else {
                    Object cglibProxy = transactionProxyFactory.getCglibProxy(o);
                    singletonObjects.put(beanName, cglibProxy);
                }
            }
        });
    }

    private static String getScanPackage() throws DocumentException {
        InputStream resourceAsStream = BeanFactory.class.getClassLoader().getResourceAsStream("beans.xml");
        SAXReader saxReader = new SAXReader();
        Document document = saxReader.read(resourceAsStream);
        Element rootElement = document.getRootElement();
        List<Element> beanList = rootElement.selectNodes("//component-scan");
        Assert.isTrue(Objects.nonNull(beanList) && beanList.size() == 1, "component-scan配置项数量异常");
        Element element = beanList.get(0);
        return element.attributeValue("base-package");
    }

    private static String resolveBasePackage(String basePackage) {
        String replace = basePackage.replace(".", "/");
        if (replace.endsWith(SEPARATOR)) {
            replace += SEPARATOR;
        }
        return replace;
    }

    private static Set<Resource> findPathMatchingResources(Enumeration<URL> resourceUrls) throws IOException {
        URL url = resourceUrls.nextElement();
        Resource resource = new UrlResource(url);
        File rootDir = resource.getFile().getAbsoluteFile();
        String fullPattern = StringUtils.replace(rootDir.getAbsolutePath(), File.separator, SEPARATOR);
        if (!PATTERN.startsWith(SEPARATOR)) {
            fullPattern += SEPARATOR;
        }
        fullPattern = fullPattern + StringUtils.replace(PATTERN, File.separator, SEPARATOR);
        Set<File> matchingFiles = new LinkedHashSet<>(8);
        doRetrieveMatchingFiles(fullPattern, rootDir, matchingFiles);
        Set<Resource> resources = new LinkedHashSet<>(matchingFiles.size());
        matchingFiles.forEach(file -> resources.add(new FileSystemResource(file)));
        return resources;
    }

    private static void findMatchSingletonClasses(Set<Resource> resources) {
        resources.forEach(r -> {
            try {
                if (validMetadata(r)) {
                    ClassReader classReader = new ClassReader(new BufferedInputStream(r.getInputStream()));
                    String className = classReader.getClassName();
                    className = StringUtils.replace(className, File.separator, ".");
                    Class<?> aClass = Class.forName(className);
                    Component component = AnnotationUtils.findAnnotation(aClass, Component.class);
                    if (Objects.nonNull(component)) {
                        String beanName = StringUtils.isEmpty(component.value()) ? getBeanName(aClass) : component.value();
                        singletonClasses.put(aClass, beanName);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            } catch (Error e1) {
                e1.printStackTrace();
            }
        });
    }

    private static boolean validMetadata(Resource r) throws IOException {
        MetadataReader metadataReader = new CachingMetadataReaderFactory().getMetadataReader(r);
        ScannedGenericBeanDefinition sbd = new ScannedGenericBeanDefinition(metadataReader);
        sbd.setResource(r);
        sbd.setSource(r);
        AnnotationMetadata metadata = sbd.getMetadata();
        return metadata.isIndependent() && (metadata.isConcrete() ||
                (metadata.isAbstract() && metadata.hasAnnotatedMethods(Lookup.class.getName())));
    }

    private static Object createBean(Class aClass, String beanName) {
        try {
            Object o = aClass.newInstance();
            singletonFactories.put(beanName, o);
            Field[] fields = aClass.getDeclaredFields();
            Stream.of(fields)
                    .filter(field -> Objects.nonNull(field.getAnnotation(Autowired.class)))
                    .forEach(field -> {
                        Class impl = findImpl(field.getType());
                        String implBeanName = singletonClasses.get(impl);
                        Object implObj = findBean(implBeanName);
                        if (Objects.isNull(implObj)) {
                            implObj = createBean(impl, implBeanName);
                        }
                        try {
                            field.setAccessible(true);
                            field.set(o, implObj);
                        } catch (Exception e) {
                            throw new RuntimeException("依赖注入失败", e);
                        }
                    });
            singletonObjects.put(beanName, o);
            singletonFactories.remove(beanName);
            return o;
        } catch (Exception e) {
            throw new RuntimeException("创建bean失败", e);
        }
    }

    private static Object findBean(String beanName) {
        Object obj = singletonObjects.get(beanName);
        if (Objects.isNull(obj)) {
            obj = singletonFactories.get(beanName);
        }
        return obj;
    }

    private static Class findImpl(Class<?> aClass) {
        Set<Class> classes = singletonClasses.keySet();
        if (!aClass.isInterface()) {
            Assert.isTrue(classes.contains(aClass), String.format("没有找到依赖注入[%s]的实现类", aClass.getName()));
            return aClass;
        }
        Optional<Class> implOptional = classes.stream().filter(c -> Arrays.asList(c.getInterfaces()).contains(aClass)).findFirst();
        Assert.isTrue(implOptional.isPresent(), String.format("没有找到依赖注入[%s]的实现类", aClass.getName()));
        return implOptional.get();
    }

    private static String getBeanName(Class clazz) {
        char[] chars = clazz.getSimpleName().toCharArray();
        chars[0] += 32;
        return String.valueOf(chars);
    }

    private static void doRetrieveMatchingFiles(String fullPattern, File dir, Set<File> result) {
        File[] files = dir.listFiles();
        for (File content : files) {
            String currPath = StringUtils.replace(content.getAbsolutePath(), File.separator, "/");
            if (content.isDirectory() && pathMatcher.matchStart(fullPattern, currPath + "/")) {
                doRetrieveMatchingFiles(fullPattern, content, result);
            }
            if (pathMatcher.match(fullPattern, currPath)) {
                result.add(content);
            }
        }
    }

    public Object getBean(String id) {
        return singletonObjects.get(id);
    }

}
