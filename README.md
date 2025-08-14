# rewrite-pom-maven-plugin

根据maven激活的profile重写pom.xml并重新打包jar文件

## 用法

```xml
 <plugin>
    <groupId>com.uke16.maven.plugin</groupId>
    <artifactId>rewrite-pom-maven-plugin</artifactId>
    <version>1.0-SNAPSHOT</version>
    <configuration>
        <skip>true</skip> <!--可选配置，为true跳过当前goal-->
    </configuration>
    <executions>
        <execution>
            <phase>package</phase>
            <goals>
                <goal>rewrite</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

