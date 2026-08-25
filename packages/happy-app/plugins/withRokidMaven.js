const { withGradleProperties, withProjectBuildGradle } = require('@expo/config-plugins');

const ROKID_REPOSITORY = "maven { url 'https://maven.rokid.com/repository/maven-public/' }";

/**
 * Makes the repository hosting Rokid's CXR-L SDK available to the generated
 * Android app. A repository declared by a library module is not used when the
 * consuming app resolves that library's transitive dependencies.
 */
const withRokidMaven = (config) => {
  config = withProjectBuildGradle(config, (gradleConfig) => {
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

  // CXR-L itself requires API 28, so this Rokid-specific fork cannot keep
  // Happy's upstream API 24 minimum without risking class-loading failures.
  return withGradleProperties(config, (gradleConfig) => {
    const existing = gradleConfig.modResults.find(
      (item) => item.type === 'property' && item.key === 'android.minSdkVersion',
    );
    if (existing) {
      existing.value = '28';
    } else {
      gradleConfig.modResults.push({
        type: 'property',
        key: 'android.minSdkVersion',
        value: '28',
      });
    }
    return gradleConfig;
  });
};

module.exports = withRokidMaven;
