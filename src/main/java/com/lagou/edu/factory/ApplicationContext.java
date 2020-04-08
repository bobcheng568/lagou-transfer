package com.lagou.edu.factory;


/**
 * @author bobcheng
 * @date 2020/4/8
 */
public class ApplicationContext {

    private ApplicationContext() {
    }

    private static ApplicationContext applicationContext = new ApplicationContext();

    public static ApplicationContext getInstance() {
        return applicationContext;
    }

    private BeanFactory beanFactory = new BeanFactory();

    public Object getBean(String id) {
        return getBeanFactory().getBean(id);
    }

    private BeanFactory getBeanFactory() {
        return beanFactory;
    }
}
