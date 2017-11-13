/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.integtests.resolve

import spock.lang.Unroll

class StrictDependenciesIntegrationTest extends AbstractStrictDependenciesIntegrationTest {

    void "can declare a strict dependency onto an external component"() {
        given:
        repository {
            group("org") {
                module("foo") {
                    version("1.0") {
                        expectGetMetadata()
                        expectGetArtifact()
                    }
                }
            }
        }

        buildFile << """
            $repository

            configurations {
                conf
            }
            dependencies {
                conf('org:foo:1.0') {
                   version {
                      strictly '1.0'
                   }
                }
            }           
        """

        when:
        run 'checkDeps'

        then:
        resolve.expectGraph {
            root(":", ":test:") {
                module("org:foo:1.0")
            }
        }

    }

    void "should fail if transitive dependency version is not compatible with the strict dependency version"() {
        given:
        repository {
            'org' {
                'foo' {
                    '1.0' {
                        expectGetMetadata()
                    }
                    '1.1'()
                }
                'bar' {
                    '1.0' {
                        dependsOn('org:foo:1.1')
                        expectGetMetadata()
                    }
                }
            }
        }

        buildFile << """
            $repository

            configurations {
                conf
            }
            dependencies {
                conf('org:foo') {
                    version {
                       strictly '1.0'
                    }
                }
                conf('org:bar:1.0')
            }           
        """

        when:
        fails 'checkDeps'

        then:
        failure.assertHasCause('Cannot find a version of \'org:foo\' that satisfies the constraints: prefers 1.0, rejects ]1.0,), prefers 1.1')

    }

    void "should pass if transitive dependency version matches exactly the strict dependency version"() {
        given:
        repository {
            group('org') {
                module('foo') {
                    version('1.0') {
                        expectGetMetadata()
                        expectGetArtifact()
                    }
                }
                module('bar') {
                    version('1.0') {
                        dependsOn 'org:foo:1.0'

                        expectGetMetadata()
                        expectGetArtifact()
                    }
                }
            }
        }

        buildFile << """
            $repository

            configurations {
                conf
            }
            dependencies {
                conf('org:foo') {
                    version {
                        strictly '1.0'
                    }
                }
                conf('org:bar:1.0')
            }           
        """

        when:
        run 'checkDeps'

        then:
        resolve.expectGraph {
            root(":", ":test:") {
                edge "org:foo:1.0", "org:foo:1.0"
                edge("org:bar:1.0", "org:bar:1.0") {
                    edge "org:foo:1.0", "org:foo:1.0"
                }
            }
        }
    }

    void "can upgrade a non-strict dependency"() {
        given:
        repository {
            group('org') {
                module('foo') {
                    version '1.0'
                    version('1.1') {
                        expectGetMetadata()
                        expectGetArtifact()
                    }
                }
                module('bar') {
                    version('1.0') {
                        dependsOn('org:foo:1.0')
                        expectGetMetadata()
                        expectGetArtifact()
                    }
                }
            }
        }

        buildFile << """
            $repository

            configurations {
                conf
            }
            dependencies {
                conf('org:foo') {
                    version {
                       strictly '1.1'
                    }
                }
                conf('org:bar:1.0')
            }           
        """

        when:
        run 'checkDeps'

        then:
        resolve.expectGraph {
            root(":", ":test:") {
                edge "org:foo:1.1", "org:foo:1.1"
                edge("org:bar:1.0", "org:bar:1.0") {
                    edge("org:foo:1.0", "org:foo:1.1").byConflictResolution()
                }
            }
        }
    }

    @Unroll
    void "should pass if transitive dependency version (#transitiveDependencyVersion) matches a strict dependency version (#directDependencyVersion)"() {
        given:
        repository {
            group('org') {
                module('foo') {
                    expectVersionListing()

                    version '1.0'
                    version '1.1'
                    version('1.2') {
                        expectGetMetadata()
                        expectGetArtifact()
                    }
                    version('1.3') {
                        expectGetMetadata()
                    }
                }
                module('bar') {
                    version('1.0') {
                        dependsOn("org:foo:$transitiveDependencyVersion")
                        expectGetMetadata()
                        expectGetArtifact()
                    }
                }
            }
        }

        buildFile << """
            $repository

            configurations {
                conf
            }
            dependencies {
                conf('org:foo') {
                    version {
                       strictly '$directDependencyVersion'
                    }
                }
                conf('org:bar:1.0')
            }
                          
        """

        when:
        run 'checkDeps'

        then:
        noExceptionThrown()

        resolve.expectGraph {
            root(":", ":test:") {
                edge("org:foo:$directDependencyVersion", "org:foo:1.2")
                edge("org:bar:1.0", "org:bar:1.0") {
                    edge("org:foo:$transitiveDependencyVersion", "org:foo:1.2")
                }
            }
        }

        where:
        directDependencyVersion << ['[1.0,1.3]', '1.2', '[1.0, 1.2]', '[1.0, 1.3]']
        transitiveDependencyVersion << ['1.2', '[1.0,1.3]', '[1.0, 1.3]', '[1.0, 1.2]']
    }

    def "should not downgrade dependency version when a transitive dependency has strict version"() {
        given:
        repository {
            'org:foo:15' {
                maybeGetMetadata()
            }
            'org:foo:17' {
                expectGetMetadata()
            }
        }

        buildFile << """
            $repository

            configurations {
                conf
            }
            dependencies {
                conf('org:foo:17')
                conf project(path: 'other', configuration: 'conf')
            }                       
        """
        file("other/build.gradle") << """
            $repository

            configurations {
                conf
            }
            dependencies {
                conf('org:foo') {
                    version {
                        strictly '15'
                    }
                }
            }       
        """
        settingsFile << "\ninclude 'other'"

        when:
        fails 'checkDeps'

        then:
        failure.assertHasCause('Cannot find a version of \'org:foo\' that satisfies the constraints: prefers 17, prefers 15, rejects ]15,)')

    }

    def "should fail if 2 strict versions disagree"() {
        given:
        repository {
            'org:foo:15' {
                maybeGetMetadata()
            }
            'org:foo:17' {
                expectGetMetadata()
            }
        }

        buildFile << """
            $repository

            configurations {
                conf
            }
            dependencies {
                conf('org:foo') {
                    version {
                        strictly '17'
                    }
                }
                conf project(path: 'other', configuration: 'conf')
            }                       
        """
        file("other/build.gradle") << """
            $repository

            configurations {
                conf
            }
            dependencies {
                conf('org:foo:15') {
                    version {
                        strictly '15'
                    }
                }
            }       
        """
        settingsFile << "\ninclude 'other'"

        when:
        fails 'checkDeps'

        then:
        failure.assertHasCause('Cannot find a version of \'org:foo\' that satisfies the constraints: prefers 17, rejects ]17,), prefers 15, rejects ]15,)')

    }

    def "should fail if 2 non overlapping strict versions ranges disagree"() {
        given:
        repository {
            group('org') {
                module('foo') {
                    expectVersionListing()

                    version('15')
                    version('16') {
                        expectGetMetadata()
                    }
                    version('17')
                    version('18') {
                        expectGetMetadata()
                    }
                }
            }
        }

        buildFile << """
            $repository

            configurations {
                conf
            }
            dependencies {
                conf('org:foo') {
                    version {
                        strictly '[15,16]'
                    }
                }
                conf project(path: 'other', configuration: 'conf')
            }                       
        """
        file("other/build.gradle") << """
            $repository

            configurations {
                conf
            }
            dependencies {
                conf('org:foo') {
                    version {
                        strictly '[17,18]'
                    }
                }
            }       
        """
        settingsFile << "\ninclude 'other'"

        when:
        fails 'checkDeps'

        then:
        failure.assertHasCause('Cannot find a version of \'org:foo\' that satisfies the constraints: prefers [15,16], rejects ]16,), prefers [17,18], rejects ]18,)')

    }

    void "should pass if strict version ranges overlap"() {
        given:
        repository {
            group('org') {
                module('foo') {
                    expectVersionListing()

                    version('1.0')
                    version('1.1')
                    version('1.2') {
                        expectGetMetadata()
                        expectGetArtifact()
                    }
                    version('1.3') {
                        expectGetMetadata()
                    }
                }
            }
        }

        buildFile << """
            $repository

            configurations {
                conf
            }
            dependencies {
                conf('org:foo') {
                    version {
                        strictly '[1.0,1.2]'
                    }
                }
                conf project(path:'other', configuration: 'conf')
            }
                                  
        """
        file("other/build.gradle") << """
            $repository

            configurations {
                conf
            }
            dependencies {
                conf('org:foo:[1.1,1.3]') {
                    version {
                        strictly '[1.1,1.3]'
                    }
                }
            }       
        """
        settingsFile << "\ninclude 'other'"

        when:
        run ':checkDeps'

        then:
        resolve.expectGraph {
            root(":", ":test:") {
                edge("org:foo:[1.0,1.2]", "org:foo:1.2")
                project(':other', 'test:other:') {
                    configuration = 'conf'
                    noArtifacts()
                    edge("org:foo:[1.1,1.3]", "org:foo:1.2")
                }
            }
        }
    }
}
