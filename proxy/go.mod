module github.com/baafoo/proxy

go 1.21

require (
	github.com/baafoo/sdk-go v1.0.0
	gopkg.in/yaml.v3 v3.0.1
)

require github.com/google/uuid v1.6.0 // indirect

replace github.com/baafoo/sdk-go => ../sdks/go/baafoo
