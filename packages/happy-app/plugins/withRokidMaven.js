const { withProjectBuildGradle } = require('@expo/config-plugins');

const ROKID_REPOSITORY = "maven { url 'https://maven.rokid.com/repository/maven-public/' }";

/**
 * Makes the repository hosting Rokid's Phone SDK available to the generated
 * Android app. A repository declared by a library module is not used when the
 * consuming app resolves that library's transitive dependencies.
 */
const withRokidMaven = (config) => withProjectBuildGradle(config, (gradleConfig) => {
  if (gradleConfig.modResults.language !== 'groovy') {
    throw new Error('withRokidMaven currently supports Groovy build.gradle files only.');
  }

  if (!gradleConfig.modResults.contents.includes('maven.rokid.com/repository/maven-public')) {
    gradleConfig.modResults.contents = gradleConfig.modResults.contents.replace(
      /allprojects\s*\{\s*repositories\s*\{/,
      (match) => `${match}\n    ${ROKID_REPOSITORY}`,
    );
  }

  return gradleConfig;
});

module.exports = withRokidMaven;
