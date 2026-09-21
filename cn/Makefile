.PHONY: demo demo-down test build

demo:
	docker compose up --build

demo-down:
	docker compose down

test:
	mvn -DskipTests=false test
	cd frontend && npm test

build:
	mvn -DskipTests package
	cd frontend && npm ci && npm run build
